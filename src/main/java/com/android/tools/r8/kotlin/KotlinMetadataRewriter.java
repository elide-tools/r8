// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.kotlin;

import static com.android.tools.r8.kotlin.KotlinMetadataUtils.INVALID_KOTLIN_INFO;
import static com.android.tools.r8.kotlin.KotlinMetadataUtils.NO_KOTLIN_INFO;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationElement;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedAnnotation;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DexValue.DexValueArray;
import com.android.tools.r8.graph.DexValue.DexValueInt;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.utils.ConsumerUtils;
import com.android.tools.r8.utils.Reporter;
import com.android.tools.r8.utils.ThreadUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import kotlinx.metadata.jvm.KotlinClassHeader;

public class KotlinMetadataRewriter {

  // Due to a bug with nested classes and the lookup of RequirementVersion, we bump all metadata
  // versions to 1.4 if compiled with kotlin 1.3 (1.1.16). For more information, see b/161885097 for
  // more information.
  private static final int[] METADATA_VERSION_1_4 = new int[] {1, 4, 0};

  private static final class WriteMetadataFieldInfo {
    final boolean writeKind;
    final boolean writeMetadataVersion;
    final boolean writeByteCodeVersion;
    final boolean writeData1;
    final boolean writeData2;
    final boolean writeExtraString;
    final boolean writePackageName;
    final boolean writeExtraInt;

    private WriteMetadataFieldInfo(
        boolean writeKind,
        boolean writeMetadataVersion,
        boolean writeByteCodeVersion,
        boolean writeData1,
        boolean writeData2,
        boolean writeExtraString,
        boolean writePackageName,
        boolean writeExtraInt) {
      this.writeKind = writeKind;
      this.writeMetadataVersion = writeMetadataVersion;
      this.writeByteCodeVersion = writeByteCodeVersion;
      this.writeData1 = writeData1;
      this.writeData2 = writeData2;
      this.writeExtraString = writeExtraString;
      this.writePackageName = writePackageName;
      this.writeExtraInt = writeExtraInt;
    }

    private static WriteMetadataFieldInfo rewriteAll() {
      return new WriteMetadataFieldInfo(true, true, true, true, true, true, true, true);
    }
  }

  private final AppView<?> appView;
  private final NamingLens lens;
  private final DexItemFactory factory;
  private final Kotlin kotlin;

  public KotlinMetadataRewriter(AppView<?> appView, NamingLens lens) {
    this.appView = appView;
    this.lens = lens;
    this.factory = appView.dexItemFactory();
    this.kotlin = factory.kotlin;
  }

  private static boolean isNotKotlinMetadata(AppView<?> appView, DexAnnotation annotation) {
    return annotation.annotation.type != appView.dexItemFactory().kotlinMetadataType;
  }

  public void runForR8(ExecutorService executorService) throws ExecutionException {
    final DexClass kotlinMetadata =
        appView.definitionFor(appView.dexItemFactory().kotlinMetadataType);
    final WriteMetadataFieldInfo writeMetadataFieldInfo =
        new WriteMetadataFieldInfo(
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.kind),
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.metadataVersion),
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.bytecodeVersion),
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.data1),
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.data2),
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.extraString),
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.packageName),
            kotlinMetadataFieldExists(kotlinMetadata, appView, kotlin.metadata.extraInt));
    ThreadUtils.processItems(
        appView.appInfo().classes(),
        clazz -> {
          KotlinClassLevelInfo kotlinInfo = clazz.getKotlinInfo();
          DexAnnotation oldMeta = clazz.annotations().getFirstMatching(factory.kotlinMetadataType);
          if (kotlinInfo == INVALID_KOTLIN_INFO) {
            // Maintain invalid kotlin info for classes.
            return;
          }
          if (oldMeta == null
              || kotlinInfo == NO_KOTLIN_INFO
              || (appView.appInfo().hasLiveness()
                  && !appView.withLiveness().appInfo().isPinned(clazz.type))) {
            // Remove @Metadata in DexAnnotation when there is no kotlin info and the type is not
            // missing.
            if (oldMeta != null) {
              clazz.setAnnotations(
                  clazz.annotations().keepIf(anno -> isNotKotlinMetadata(appView, anno)));
            }
            return;
          }
          writeKotlinInfoToAnnotation(clazz, kotlinInfo, oldMeta, writeMetadataFieldInfo);
        },
        executorService);
  }

  public void runForD8(ExecutorService executorService) throws ExecutionException {
    if (lens.isIdentityLens()) {
      return;
    }
    final Kotlin kotlin = factory.kotlin;
    final Reporter reporter = appView.options().reporter;
    final WriteMetadataFieldInfo writeMetadataFieldInfo = WriteMetadataFieldInfo.rewriteAll();
    ThreadUtils.processItems(
        appView.appInfo().classes(),
        clazz -> {
          DexAnnotation metadata = clazz.annotations().getFirstMatching(factory.kotlinMetadataType);
          if (metadata == null) {
            return;
          }
          final KotlinClassLevelInfo kotlinInfo =
              KotlinClassMetadataReader.getKotlinInfo(
                  kotlin, clazz, factory, reporter, ConsumerUtils.emptyConsumer(), metadata);
          if (kotlinInfo == NO_KOTLIN_INFO) {
            return;
          }
          writeKotlinInfoToAnnotation(clazz, kotlinInfo, metadata, writeMetadataFieldInfo);
        },
        executorService);
  }

  private void writeKotlinInfoToAnnotation(
      DexClass clazz,
      KotlinClassLevelInfo kotlinInfo,
      DexAnnotation oldMeta,
      WriteMetadataFieldInfo writeMetadataFieldInfo) {
    try {
      KotlinClassHeader kotlinClassHeader = kotlinInfo.rewrite(clazz, appView, lens);
      DexAnnotation newMeta =
          createKotlinMetadataAnnotation(
              kotlinClassHeader,
              kotlinInfo.getPackageName(),
              getMaxVersion(METADATA_VERSION_1_4, kotlinInfo.getMetadataVersion()),
              writeMetadataFieldInfo);
      clazz.setAnnotations(clazz.annotations().rewrite(anno -> anno == oldMeta ? newMeta : anno));
    } catch (Throwable t) {
      appView
          .options()
          .reporter
          .warning(KotlinMetadataDiagnostic.unexpectedErrorWhenRewriting(clazz.type, t));
    }
  }

  private boolean kotlinMetadataFieldExists(
      DexClass kotlinMetadata, AppView<?> appView, DexString fieldName) {
    if (!appView.appInfo().hasLiveness()) {
      return true;
    }
    if (kotlinMetadata == null || kotlinMetadata.isNotProgramClass()) {
      return true;
    }
    return kotlinMetadata.methods(method -> method.method.name == fieldName).iterator().hasNext();
  }

  private DexAnnotation createKotlinMetadataAnnotation(
      KotlinClassHeader header,
      String packageName,
      int[] metadataVersion,
      WriteMetadataFieldInfo writeMetadataFieldInfo) {
    List<DexAnnotationElement> elements = new ArrayList<>();
    if (writeMetadataFieldInfo.writeMetadataVersion) {
      elements.add(
          new DexAnnotationElement(
              kotlin.metadata.metadataVersion, createIntArray(metadataVersion)));
    }
    if (writeMetadataFieldInfo.writeByteCodeVersion) {
      elements.add(
          new DexAnnotationElement(
              kotlin.metadata.bytecodeVersion, createIntArray(header.getBytecodeVersion())));
    }
    if (writeMetadataFieldInfo.writeKind) {
      elements.add(
          new DexAnnotationElement(kotlin.metadata.kind, DexValueInt.create(header.getKind())));
    }
    if (writeMetadataFieldInfo.writeData1) {
      elements.add(
          new DexAnnotationElement(kotlin.metadata.data1, createStringArray(header.getData1())));
    }
    if (writeMetadataFieldInfo.writeData2) {
      elements.add(
          new DexAnnotationElement(kotlin.metadata.data2, createStringArray(header.getData2())));
    }
    if (writeMetadataFieldInfo.writePackageName && packageName != null && !packageName.isEmpty()) {
      elements.add(
          new DexAnnotationElement(
              kotlin.metadata.packageName, new DexValueString(factory.createString(packageName))));
    }
    if (writeMetadataFieldInfo.writeExtraString && !header.getExtraString().isEmpty()) {
      elements.add(
          new DexAnnotationElement(
              kotlin.metadata.extraString,
              new DexValueString(factory.createString(header.getExtraString()))));
    }
    if (writeMetadataFieldInfo.writeExtraInt && header.getExtraInt() != 0) {
      elements.add(
          new DexAnnotationElement(
              kotlin.metadata.extraInt, DexValueInt.create(header.getExtraInt())));
    }
    DexEncodedAnnotation encodedAnnotation =
        new DexEncodedAnnotation(
            factory.kotlinMetadataType, elements.toArray(DexAnnotationElement.EMPTY_ARRAY));
    return new DexAnnotation(DexAnnotation.VISIBILITY_RUNTIME, encodedAnnotation);
  }

  private DexValueArray createIntArray(int[] data) {
    DexValue[] values = new DexValue[data.length];
    for (int i = 0; i < data.length; i++) {
      values[i] = DexValueInt.create(data[i]);
    }
    return new DexValueArray(values);
  }

  private DexValueArray createStringArray(String[] data) {
    DexValue[] values = new DexValue[data.length];
    for (int i = 0; i < data.length; i++) {
      values[i] = new DexValueString(factory.createString(data[i]));
    }
    return new DexValueArray(values);
  }

  // We are not sure that the format is <Major>-<Minor>-<Patch>, the format can be: <Major>-<Minor>.
  private int[] getMaxVersion(int[] one, int[] other) {
    assert one.length == 2 || one.length == 3;
    assert other.length == 2 || other.length == 3;
    if (one[0] != other[0]) {
      return one[0] > other[0] ? one : other;
    }
    if (one[1] != other[1]) {
      return one[1] > other[1] ? one : other;
    }
    int patchOne = one.length >= 3 ? one[2] : 0;
    int patchOther = other.length >= 3 ? other[2] : 0;
    if (patchOne != patchOther) {
      return patchOne > patchOther ? one : other;
    }
    // They are equal up to patch, just return one.
    return one;
  }
}
