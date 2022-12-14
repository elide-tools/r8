// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex.container;

import static com.android.tools.r8.dex.Constants.DATA_OFF_OFFSET;
import static com.android.tools.r8.dex.Constants.DATA_SIZE_OFFSET;
import static com.android.tools.r8.dex.Constants.MAP_OFF_OFFSET;
import static com.android.tools.r8.dex.Constants.STRING_IDS_OFF_OFFSET;
import static com.android.tools.r8.dex.Constants.STRING_IDS_SIZE_OFFSET;
import static com.android.tools.r8.dex.Constants.TYPE_STRING_ID_ITEM;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.ClassFileConsumer.ArchiveConsumer;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.dex.CompatByteBuffer;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.maindexlist.MainDexListTests;
import com.android.tools.r8.transformers.ClassTransformer;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.ZipUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

@RunWith(Parameterized.class)
public class DexContainerFormatBasicTest extends TestBase {

  @Parameter() public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withNoneRuntime().build();
  }

  private static Path inputA;
  private static Path inputB;

  @BeforeClass
  public static void generateTestApplications() throws Throwable {
    // Build two applications in different packages both with required multidex due to number
    // of methods.
    inputA = getStaticTemp().getRoot().toPath().resolve("application_a.jar");
    inputB = getStaticTemp().getRoot().toPath().resolve("application_b.jar");

    generateApplication(inputA, "a", 10);
    generateApplication(inputB, "b", 10);
  }

  @Test
  public void testNonContainerD8() throws Exception {
    Path outputA =
        testForD8(Backend.DEX)
            .addProgramFiles(inputA)
            .setMinApi(AndroidApiLevel.L)
            .compile()
            .writeToZip();
    assertEquals(2, unzipContent(outputA).size());

    Path outputB =
        testForD8(Backend.DEX)
            .addProgramFiles(inputB)
            .setMinApi(AndroidApiLevel.L)
            .compile()
            .writeToZip();
    assertEquals(2, unzipContent(outputB).size());

    Path outputMerged =
        testForD8(Backend.DEX)
            .addProgramFiles(outputA, outputB)
            .setMinApi(AndroidApiLevel.L)
            .compile()
            .writeToZip();
    assertEquals(4, unzipContent(outputMerged).size());
  }

  @Test
  public void testD8Experiment() throws Exception {
    Path outputFromDexing =
        testForD8(Backend.DEX)
            .addProgramFiles(inputA)
            .setMinApi(AndroidApiLevel.L)
            .addOptionsModification(
                options -> options.getTestingOptions().dexContainerExperiment = true)
            .compile()
            .writeToZip();
    validateSingleContainerDex(outputFromDexing);
  }

  @Test
  public void testD8Experiment2() throws Exception {
    Path outputA =
        testForD8(Backend.DEX)
            .addProgramFiles(inputA)
            .setMinApi(AndroidApiLevel.L)
            .addOptionsModification(
                options -> options.getTestingOptions().dexContainerExperiment = true)
            .compile()
            .writeToZip();
    validateSingleContainerDex(outputA);

    Path outputB =
        testForD8(Backend.DEX)
            .addProgramFiles(inputB)
            .setMinApi(AndroidApiLevel.L)
            .addOptionsModification(
                options -> options.getTestingOptions().dexContainerExperiment = true)
            .compile()
            .writeToZip();
    validateSingleContainerDex(outputB);
  }

  private void validateSingleContainerDex(Path output) throws IOException {
    List<byte[]> dexes = unzipContent(output);
    assertEquals(1, dexes.size());
    validateStringIdsSizeAndOffsets(dexes.get(0));
  }

  private void validateStringIdsSizeAndOffsets(byte[] dex) {
    CompatByteBuffer buffer = CompatByteBuffer.wrap(dex);
    setByteOrder(buffer);

    IntList sections = new IntArrayList();
    int offset = 0;
    while (offset < buffer.capacity()) {
      sections.add(offset);
      int dataSize = buffer.getInt(offset + DATA_SIZE_OFFSET);
      int dataOffset = buffer.getInt(offset + DATA_OFF_OFFSET);
      offset = dataOffset + dataSize;
    }
    assertEquals(buffer.capacity(), offset);

    int lastOffset = sections.getInt(sections.size() - 1);
    int stringIdsSize = buffer.getInt(lastOffset + STRING_IDS_SIZE_OFFSET);
    int stringIdsOffset = buffer.getInt(lastOffset + STRING_IDS_OFF_OFFSET);

    for (Integer sectionOffset : sections) {
      assertEquals(stringIdsSize, buffer.getInt(sectionOffset + STRING_IDS_SIZE_OFFSET));
      assertEquals(stringIdsOffset, buffer.getInt(sectionOffset + STRING_IDS_OFF_OFFSET));
      assertEquals(stringIdsSize, getSizeFromMap(TYPE_STRING_ID_ITEM, buffer, sectionOffset));
      assertEquals(stringIdsOffset, getOffsetFromMap(TYPE_STRING_ID_ITEM, buffer, sectionOffset));
    }
  }

  private int getSizeFromMap(int type, CompatByteBuffer buffer, int offset) {
    int mapOffset = buffer.getInt(offset + MAP_OFF_OFFSET);
    buffer.position(mapOffset);
    int mapSize = buffer.getInt();
    for (int i = 0; i < mapSize; i++) {
      int sectionType = buffer.getShort();
      buffer.getShort(); // Skip unused.
      int sectionSize = buffer.getInt();
      buffer.getInt(); // Skip offset.
      if (type == sectionType) {
        return sectionSize;
      }
    }
    throw new RuntimeException("Not found");
  }

  private int getOffsetFromMap(int type, CompatByteBuffer buffer, int offset) {
    int mapOffset = buffer.getInt(offset + MAP_OFF_OFFSET);
    buffer.position(mapOffset);
    int mapSize = buffer.getInt();
    for (int i = 0; i < mapSize; i++) {
      int sectionType = buffer.getShort();
      buffer.getShort(); // Skip unused.
      buffer.getInt(); // SKip size.
      int sectionOffset = buffer.getInt();
      if (type == sectionType) {
        return sectionOffset;
      }
    }
    throw new RuntimeException("Not found");
  }

  private void setByteOrder(CompatByteBuffer buffer) {
    // Make sure we set the right endian for reading.
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    int endian = buffer.getInt(Constants.ENDIAN_TAG_OFFSET);
    if (endian == Constants.REVERSE_ENDIAN_CONSTANT) {
      buffer.order(ByteOrder.BIG_ENDIAN);
    } else {
      if (endian != Constants.ENDIAN_CONSTANT) {
        throw new CompilationError("Unable to determine endianess for reading dex file.");
      }
    }
  }

  private List<byte[]> unzipContent(Path zip) throws IOException {
    List<byte[]> result = new ArrayList<>();
    ZipUtils.iter(zip, (entry, inputStream) -> result.add(ByteStreams.toByteArray(inputStream)));
    return result;
  }

  private static void generateApplication(Path applicationJar, String rootPackage, int methodCount)
      throws Throwable {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (int i = 0; i < 10000; ++i) {
      String pkg = rootPackage + "." + (i % 2 == 0 ? "a" : "b");
      String className = "Class" + i;
      builder.add(pkg + "." + className);
    }
    List<String> classes = builder.build();

    generateApplication(applicationJar, classes, methodCount);
  }

  private static void generateApplication(Path output, List<String> classes, int methodCount)
      throws IOException {
    ArchiveConsumer consumer = new ArchiveConsumer(output);
    for (String typename : classes) {
      String descriptor = DescriptorUtils.javaTypeToDescriptor(typename);
      byte[] bytes =
          transformer(MainDexListTests.ClassStub.class)
              .setClassDescriptor(descriptor)
              .addClassTransformer(
                  new ClassTransformer() {
                    @Override
                    public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions) {
                      // This strips <init>() too.
                      if (name.equals("methodStub")) {
                        for (int i = 0; i < methodCount; i++) {
                          MethodVisitor mv =
                              super.visitMethod(
                                  access, "method" + i, descriptor, signature, exceptions);
                          mv.visitCode();
                          mv.visitInsn(Opcodes.RETURN);
                          mv.visitMaxs(0, 0);
                          mv.visitEnd();
                        }
                      }
                      return null;
                    }
                  })
              .transform();
      consumer.accept(ByteDataView.of(bytes), descriptor, null);
    }
    consumer.finished(null);
  }

  // Simple stub/template for generating the input classes.
  public static class ClassStub {
    public static void methodStub() {}
  }
}
