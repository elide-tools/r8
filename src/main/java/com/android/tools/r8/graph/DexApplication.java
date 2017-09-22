// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ProgramClassCollection;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.primitives.Bytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public abstract class DexApplication {

  // Maps type into class, may be used concurrently.
  ProgramClassCollection programClasses;

  public final ImmutableSet<DexType> mainDexList;
  public final byte[] deadCode;

  private final ClassNameMapper proguardMap;

  public final Timing timing;

  public final DexItemFactory dexItemFactory;

  // Information on the lexicographically largest string referenced from code.
  public final DexString highestSortingString;

  /**
   * Constructor should only be invoked by the DexApplication.Builder.
   */
  DexApplication(
      ClassNameMapper proguardMap,
      ProgramClassCollection programClasses,
      ImmutableSet<DexType> mainDexList,
      byte[] deadCode,
      DexItemFactory dexItemFactory,
      DexString highestSortingString,
      Timing timing) {
    assert programClasses != null;
    this.proguardMap = proguardMap;
    this.programClasses = programClasses;
    this.mainDexList = mainDexList;
    this.deadCode = deadCode;
    this.dexItemFactory = dexItemFactory;
    this.highestSortingString = highestSortingString;
    this.timing = timing;
  }

  public abstract Builder<?> builder();

  // Reorder classes randomly. Note that the order of classes in program or library
  // class collections should not matter for compilation of valid code and when running
  // with assertions enabled we reorder the classes randomly to catch possible issues.
  // Also note that the order may add to non-determinism in reporting errors for invalid
  // code, but this non-determinism exists even with the same order of classes since we
  // may process classes concurrently and fail-fast on the first error.
  private <T> boolean reorderClasses(List<T> classes) {
    Collections.shuffle(classes);
    return true;
  }

  public List<DexProgramClass> classes() {
    programClasses.forceLoad(type -> true);
    List<DexProgramClass> classes = programClasses.getAllClasses();
    assert reorderClasses(classes);
    return classes;
  }

  public abstract DexClass definitionFor(DexType type);

  public DexProgramClass programDefinitionFor(DexType type) {
    DexClass clazz = programClasses.get(type);
    return clazz == null ? null : clazz.asProgramClass();
  }

  public abstract String toString();

  public ClassNameMapper getProguardMap() {
    return proguardMap;
  }

  private void disassemble(DexEncodedMethod method, ClassNameMapper naming, Path outputDir) {
    if (method.getCode() != null) {
      PrintStream ps = System.out;
      try {
        String clazzName;
        String methodName;
        if (naming != null) {
          clazzName = naming.originalNameOf(method.method.holder);
          methodName = naming.originalSignatureOf(method.method).toString();
        } else {
          clazzName = method.method.holder.toSourceString();
          methodName = method.method.name.toString();
        }
        if (outputDir != null) {
          Path directory = outputDir.resolve(clazzName.replace('.', '/'));
          String name = methodName + ".dump";
          if (name.length() > 200) {
            name = StringUtils.computeMD5Hash(name);
          }
          Files.createDirectories(directory);
          ps = new PrintStream(Files.newOutputStream(directory.resolve(name)));
        }
        ps.println("Bytecode for");
        ps.println("Class: '" + clazzName + "'");
        ps.println("Method: '" + methodName + "':");
        ps.println(method.getCode().toString(method, naming));
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        if (outputDir != null) {
          ps.flush();
          ps.close();
        }
      }
    }
  }

  /**
   * Write disassembly for the application code in the provided directory.
   *
   * <p>If no directory is provided everything is written to System.out.
   */
  public void disassemble(Path outputDir, InternalOptions options) {
    for (DexProgramClass clazz : programClasses.getAllClasses()) {
      clazz.forEachMethod(method -> {
        if (options.methodMatchesFilter(method)) {
          disassemble(method, getProguardMap(), outputDir);
        }
      });
    }
  }

  /**
   * Return smali source for the application code.
   */
  public String smali(InternalOptions options) {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(os);
    smali(options, ps);
    return new String(os.toByteArray(), StandardCharsets.UTF_8);
  }

  private void writeClassHeader(DexClass clazz, PrintStream ps) {
    StringBuilder builder = new StringBuilder();
    builder.append(".class ");
    builder.append(clazz.accessFlags.toSmaliString());
    builder.append(" ");
    builder.append(clazz.type.toSmaliString());
    builder.append("\n\n");
    if (clazz.type != dexItemFactory.objectType) {
      builder.append(".super ");
      builder.append(clazz.superType.toSmaliString());
      builder.append("\n");
      for (DexType iface : clazz.interfaces.values) {
        builder.append(".implements ");
        builder.append(iface.toSmaliString());
        builder.append("\n");
      }
    }
    ps.append(builder.toString());
  }

  private void writeClassFooter(DexClass clazz, PrintStream ps) {
    StringBuilder builder = new StringBuilder();
    builder.append("# End of class ");
    builder.append(clazz.type.toSmaliString());
    builder.append("\n");
    ps.append(builder.toString());
  }

  /**
   * Write smali source for the application code on the provided PrintStream.
   */
  public void smali(InternalOptions options, PrintStream ps) {
    List<DexProgramClass> classes = programClasses.getAllClasses();
    classes.sort(Comparator.comparing(DexProgramClass::toSourceString));
    boolean firstClass = true;
    for (DexClass clazz : classes) {
      boolean classHeaderWritten = false;
      if (!options.hasMethodsFilter()) {
        if (!firstClass) {
          ps.append("\n");
          firstClass = false;
        }
        writeClassHeader(clazz, ps);
        classHeaderWritten = true;
      }
      for (DexEncodedMethod method : clazz.virtualMethods()) {
        if (options.methodMatchesFilter(method)) {
          if (!classHeaderWritten) {
            if (!firstClass) {
              ps.append("\n");
              firstClass = false;
            }
            writeClassHeader(clazz, ps);
            classHeaderWritten = true;
          }
          ps.append("\n");
          ps.append(method.toSmaliString(getProguardMap()));
        }
      }
      for (DexEncodedMethod method : clazz.directMethods()) {
        if (options.methodMatchesFilter(method)) {
          if (!classHeaderWritten) {
            if (!firstClass) {
              ps.append("\n");
              firstClass = false;
            }
            writeClassHeader(clazz, ps);
            classHeaderWritten = true;
          }
          ps.append("\n");
          ps.append(method.toSmaliString(getProguardMap()));
        }
      }
      if (classHeaderWritten) {
        ps.append("\n");
        writeClassFooter(clazz, ps);
      }
    }
  }

  public abstract static class Builder<T extends Builder> {
    // We handle program class collection separately from classpath
    // and library class collections. Since while we assume program
    // class collection should always be fully loaded and thus fully
    // represented by the map (making it easy, for example, adding
    // new or removing existing classes), classpath and library
    // collections will be considered monolithic collections.

    final List<DexProgramClass> programClasses;

    public final DexItemFactory dexItemFactory;
    ClassNameMapper proguardMap;
    final Timing timing;

    DexString highestSortingString;
    byte[] deadCode;
    final Set<DexType> mainDexList = Sets.newIdentityHashSet();
    private final Collection<DexProgramClass> synthesizedClasses;

    public Builder(DexItemFactory dexItemFactory, Timing timing) {
      this.programClasses = new ArrayList<>();
      this.dexItemFactory = dexItemFactory;
      this.timing = timing;
      this.deadCode = null;
      this.synthesizedClasses = new ArrayList<>();
    }

    abstract T self();

    public Builder(DexApplication application) {
      programClasses = application.programClasses.getAllClasses();
      proguardMap = application.getProguardMap();
      timing = application.timing;
      highestSortingString = application.highestSortingString;
      dexItemFactory = application.dexItemFactory;
      mainDexList.addAll(application.mainDexList);
      deadCode = application.deadCode;
      synthesizedClasses = new ArrayList<>();
    }

    public synchronized T setProguardMap(ClassNameMapper proguardMap) {
      assert this.proguardMap == null;
      this.proguardMap = proguardMap;
      return self();
    }

    public synchronized T replaceProgramClasses(List<DexProgramClass> newProgramClasses) {
      assert newProgramClasses != null;
      this.programClasses.clear();
      this.programClasses.addAll(newProgramClasses);
      return self();
    }

    public T appendDeadCode(byte[] deadCodeAtAnotherRound) {
      if (deadCodeAtAnotherRound == null) {
        return self();
      }
      if (this.deadCode == null) {
        this.deadCode = deadCodeAtAnotherRound;
        return self();
      }
      // Concatenate existing byte[] and the given byte[].
      this.deadCode = Bytes.concat(this.deadCode, deadCodeAtAnotherRound);
      return self();
    }

    public synchronized T setHighestSortingString(DexString value) {
      highestSortingString = value;
      return self();
    }

    public synchronized T addProgramClass(DexProgramClass clazz) {
      programClasses.add(clazz);
      return self();
    }

    public synchronized T addSynthesizedClass(
        DexProgramClass synthesizedClass, boolean addToMainDexList) {
      assert synthesizedClass.isProgramClass() : "All synthesized classes must be program classes";
      addProgramClass(synthesizedClass);
      synthesizedClasses.add(synthesizedClass);
      if (addToMainDexList && !mainDexList.isEmpty()) {
        mainDexList.add(synthesizedClass.type);
      }
      return self();
    }

    public Collection<DexProgramClass> getProgramClasses() {
      return programClasses;
    }

    public Collection<DexProgramClass> getSynthesizedClasses() {
      return synthesizedClasses;
    }

    public Set<DexType> getMainDexList() {
      return mainDexList;
    }

    public Builder addToMainDexList(Collection<DexType> mainDexList) {
      this.mainDexList.addAll(mainDexList);
      return this;
    }

    public abstract DexApplication build();
  }

  public static LazyLoadedDexApplication.Builder builder(DexItemFactory factory, Timing timing) {
    return new LazyLoadedDexApplication.Builder(factory, timing);
  }

  public DirectMappedDexApplication asDirect() {
    throw new Unreachable("Cannot use a LazyDexApplication where a DirectDexApplication is"
        + " expected.");
  }

  public abstract DirectMappedDexApplication toDirect();
}
