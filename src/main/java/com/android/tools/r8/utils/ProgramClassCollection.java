// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.dex.ApplicationReader.ProgramClassConflictResolver;
import com.android.tools.r8.errors.DuplicateTypesDiagnostic;
import com.android.tools.r8.graph.ClassKind;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.references.Reference;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Represents a collection of library classes. */
public class ProgramClassCollection extends ClassMap<DexProgramClass> {

  private final ProgramClassConflictResolver conflictResolver;

  public static ProgramClassCollection create(
      List<DexProgramClass> classes, ProgramClassConflictResolver conflictResolver) {
    // We have all classes preloaded, but not necessarily without conflicts.
    ConcurrentHashMap<DexType, Supplier<DexProgramClass>> map = new ConcurrentHashMap<>();
    for (DexProgramClass clazz : classes) {
      map.merge(
          clazz.type, clazz, (a, b) -> conflictResolver.resolveClassConflict(a.get(), b.get()));
    }
    return new ProgramClassCollection(map, conflictResolver);
  }

  private ProgramClassCollection(
      ConcurrentHashMap<DexType, Supplier<DexProgramClass>> classes,
      ProgramClassConflictResolver conflictResolver) {
    super(classes, null);
    this.conflictResolver = conflictResolver;
  }

  @Override
  public String toString() {
    return "program classes: " + super.toString();
  }

  @Override
  DexProgramClass resolveClassConflict(DexProgramClass a, DexProgramClass b) {
    return conflictResolver.resolveClassConflict(a, b);
  }

  @Override
  Supplier<DexProgramClass> getTransparentSupplier(DexProgramClass clazz) {
    return clazz;
  }

  @Override
  ClassKind<DexProgramClass> getClassKind() {
    return ClassKind.PROGRAM;
  }

  public static ProgramClassConflictResolver defaultConflictResolver(Reporter reporter) {
    // The default conflict resolver only merges synthetic classes generated by D8 correctly.
    // All other conflicts are reported as a fatal error.
    return (DexProgramClass a, DexProgramClass b) -> {
      assert a.type == b.type;
      if (a.accessFlags.isSynthetic() && b.accessFlags.isSynthetic()) {
        return mergeClasses(reporter, a, b);
      }
      throw reportDuplicateTypes(reporter, a, b);
    };
  }

  private static RuntimeException reportDuplicateTypes(
      Reporter reporter, DexProgramClass a, DexProgramClass b) {
    throw reporter.fatalError(
        new DuplicateTypesDiagnostic(
            Reference.classFromDescriptor(a.type.toDescriptorString()),
            ImmutableList.of(a.getOrigin(), b.getOrigin())));
  }

  private static DexProgramClass mergeClasses(
      Reporter reporter, DexProgramClass a, DexProgramClass b) {
    if (a.type.isLegacySynthesizedTypeAllowedDuplication()) {
      assert assertEqualClasses(a, b);
      return a;
    }
    throw reportDuplicateTypes(reporter, a, b);
  }

  private static boolean assertEqualClasses(DexProgramClass a, DexProgramClass b) {
    assert a.getMethodCollection().numberOfDirectMethods()
        == b.getMethodCollection().numberOfDirectMethods();
    assert a.getMethodCollection().size() == b.getMethodCollection().size();
    return true;
  }
}
