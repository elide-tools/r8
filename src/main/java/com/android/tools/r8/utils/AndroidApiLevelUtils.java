// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import static com.android.tools.r8.graph.DexLibraryClass.asLibraryClassOrNull;

import com.android.tools.r8.androidapi.AndroidApiLevelCompute;
import com.android.tools.r8.androidapi.ComputedApiLevel;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMember;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.LibraryClass;
import com.android.tools.r8.graph.LibraryDefinition;
import com.android.tools.r8.graph.LibraryMethod;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.optimize.inliner.NopWhyAreYouNotInliningReporter;
import com.android.tools.r8.ir.optimize.inliner.WhyAreYouNotInliningReporter;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.Set;

public class AndroidApiLevelUtils {

  public static boolean isApiSafeForInlining(
      ProgramMethod caller, ProgramMethod inlinee, InternalOptions options) {
    return isApiSafeForInlining(
        caller, inlinee, options, NopWhyAreYouNotInliningReporter.getInstance());
  }

  public static boolean isApiSafeForInlining(
      ProgramMethod caller,
      ProgramMethod inlinee,
      InternalOptions options,
      WhyAreYouNotInliningReporter whyAreYouNotInliningReporter) {
    if (!options.apiModelingOptions().isApiCallerIdentificationEnabled()) {
      return true;
    }
    if (caller.getHolderType() == inlinee.getHolderType()) {
      return true;
    }
    ComputedApiLevel callerApiLevelForCode = caller.getDefinition().getApiLevelForCode();
    if (callerApiLevelForCode.isUnknownApiLevel()) {
      whyAreYouNotInliningReporter.reportCallerHasUnknownApiLevel();
      return false;
    }
    // For inlining we only measure if the code has invokes into the library.
    ComputedApiLevel inlineeApiLevelForCode = inlinee.getDefinition().getApiLevelForCode();
    if (!caller
        .getDefinition()
        .getApiLevelForCode()
        .isGreaterThanOrEqualTo(inlineeApiLevelForCode)) {
      whyAreYouNotInliningReporter.reportInlineeHigherApiCall(
          callerApiLevelForCode, inlineeApiLevelForCode);
      return false;
    }
    return true;
  }

  public static ComputedApiLevel getApiReferenceLevelForMerging(
      AppView<?> appView, AndroidApiLevelCompute apiLevelCompute, DexProgramClass clazz) {
    // The api level of a class is the max level of it's members, super class and interfaces.
    return getMembersApiReferenceLevelForMerging(
        clazz,
        apiLevelCompute.computeApiLevelForDefinition(
            clazz.allImmediateSupertypes(), apiLevelCompute.getPlatformApiLevelOrUnknown(appView)));
  }

  private static ComputedApiLevel getMembersApiReferenceLevelForMerging(
      DexProgramClass clazz, ComputedApiLevel memberLevel) {
    // Based on b/138781768#comment57 there is almost no penalty for having an unknown reference
    // as long as we are not invoking or accessing a field on it. Therefore we can disregard static
    // types of fields and only consider method code api levels.
    for (DexEncodedMethod method : clazz.methods()) {
      if (method.hasCode()) {
        memberLevel = memberLevel.max(method.getApiLevelForCode());
      }
      if (memberLevel.isUnknownApiLevel()) {
        return memberLevel;
      }
    }
    return memberLevel;
  }

  public static boolean isApiSafeForMemberRebinding(
      LibraryMethod method,
      DexMethod original,
      AndroidApiLevelCompute androidApiLevelCompute,
      InternalOptions options) {
    // If we are not using the api database and we have the platform build, then we assume we are
    // running with boot class path as min api and all definitions are accessible at runtime.
    if (!androidApiLevelCompute.isEnabled()) {
      assert !options.apiModelingOptions().enableLibraryApiModeling;
      return options.isAndroidPlatformBuildOrMinApiPlatform();
    }
    assert options.apiModelingOptions().enableLibraryApiModeling;
    ComputedApiLevel apiLevel =
        androidApiLevelCompute.computeApiLevelForLibraryReference(
            method.getReference(), ComputedApiLevel.unknown());
    if (apiLevel.isUnknownApiLevel()) {
      return false;
    }
    ComputedApiLevel apiLevelOfOriginal =
        androidApiLevelCompute.computeApiLevelForLibraryReference(
            original, ComputedApiLevel.unknown());
    if (apiLevelOfOriginal.isUnknownApiLevel()) {
      return false;
    }
    return apiLevelOfOriginal.max(apiLevel).isLessThanOrEqualTo(options.getMinApiLevel()).isTrue();
  }

  public static boolean isApiSafeForReference(LibraryDefinition definition, AppView<?> appView) {
    if (appView.options().isAndroidPlatformBuildOrMinApiPlatform()) {
      assert definition != null;
      return true;
    }
    return isApiSafeForReference(
        definition, appView.apiLevelCompute(), appView.options(), appView.dexItemFactory());
  }

  private static boolean isApiSafeForReference(
      LibraryDefinition definition,
      AndroidApiLevelCompute androidApiLevelCompute,
      InternalOptions options,
      DexItemFactory factory) {
    if (!options.apiModelingOptions().enableApiCallerIdentification) {
      return factory.libraryTypesAssumedToBePresent.contains(definition.getContextType());
    }
    ComputedApiLevel apiLevel =
        androidApiLevelCompute.computeApiLevelForLibraryReference(
            definition.getReference(), ComputedApiLevel.unknown());
    return apiLevel.isLessThanOrEqualTo(options.getMinApiLevel()).isTrue();
  }

  private static boolean isApiSafeForReference(
      LibraryDefinition newDefinition, LibraryDefinition oldDefinition, AppView<?> appView) {
    assert appView.options().apiModelingOptions().enableApiCallerIdentification;
    assert !isApiSafeForReference(newDefinition, appView)
        : "Clients should first check if the definition is present on all apis since the min api";
    AndroidApiLevelCompute androidApiLevelCompute = appView.apiLevelCompute();
    ComputedApiLevel apiLevel =
        androidApiLevelCompute.computeApiLevelForLibraryReference(
            newDefinition.getReference(), ComputedApiLevel.unknown());
    if (apiLevel.isUnknownApiLevel()) {
      return false;
    }
    ComputedApiLevel apiLevelOfOriginal =
        androidApiLevelCompute.computeApiLevelForLibraryReference(
            oldDefinition.getReference(), ComputedApiLevel.unknown());
    return apiLevel.isLessThanOrEqualTo(apiLevelOfOriginal).isTrue();
  }

  public static boolean isApiSafeForTypeStrengthening(
      DexType newType, DexType oldType, AppView<? extends AppInfoWithClassHierarchy> appView) {
    // Type strengthening only applies to reference types.
    assert newType.isReferenceType();
    assert oldType.isReferenceType();
    assert newType != oldType;
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    DexType newBaseType = newType.toBaseType(dexItemFactory);
    if (newBaseType.isPrimitiveType()) {
      // Array of primitives is available on all api levels.
      return true;
    }
    assert newBaseType.isClassType();
    DexClass newBaseClass = appView.definitionFor(newBaseType);
    if (newBaseClass == null) {
      // This could be a library class that is only available on newer api levels.
      return false;
    }
    if (!newBaseClass.isLibraryClass()) {
      // Program and classpath classes are not api level dependent.
      return true;
    }
    if (!appView.options().apiModelingOptions().isApiCallerIdentificationEnabled()) {
      // Conservatively bail out if we don't have api modeling.
      return appView.options().isAndroidPlatformBuildOrMinApiPlatform();
    }
    LibraryClass newBaseLibraryClass = newBaseClass.asLibraryClass();
    if (isApiSafeForReference(newBaseLibraryClass, appView)) {
      // Library class is present on all api levels since min api.
      return true;
    }
    // Check if the new library class is present since the api level of the old type.
    DexType oldBaseType = oldType.toBaseType(dexItemFactory);
    assert oldBaseType.isClassType();
    LibraryClass oldBaseLibraryClass = asLibraryClassOrNull(appView.definitionFor(oldBaseType));
    return oldBaseLibraryClass != null
        && isApiSafeForReference(newBaseLibraryClass, oldBaseLibraryClass, appView);
  }

  public static Pair<DexClass, ComputedApiLevel> findAndComputeApiLevelForLibraryDefinition(
      AppView<?> appView,
      AppInfoWithClassHierarchy appInfo,
      DexClass holder,
      DexMember<?, ?> reference) {
    AndroidApiLevelCompute apiLevelCompute = appView.apiLevelCompute();
    if (holder.isLibraryClass()) {
      return Pair.create(
          holder,
          apiLevelCompute.computeApiLevelForLibraryReference(
              reference, ComputedApiLevel.unknown()));
    }
    // The API database do not allow for resolving into it (since that is not stable), and it is
    // therefore designed in a way where all members of classes can be queried on any sub-type with
    // the api level for where it is reachable. It is therefore sufficient for us, to figure out if
    // an instruction is a library call, to either find a program definition or to find the library
    // frontier.
    // Scan through the type hierarchy to find the first library class or program definition.
    DexClass firstClassWithReferenceOrLibraryClass =
        firstLibraryClassOrProgramClassWithDefinition(appInfo, holder, reference);
    if (firstClassWithReferenceOrLibraryClass == null) {
      return Pair.create(null, ComputedApiLevel.unknown());
    }
    if (!firstClassWithReferenceOrLibraryClass.isLibraryClass()) {
      return Pair.create(firstClassWithReferenceOrLibraryClass, appView.computedMinApiLevel());
    }
    ComputedApiLevel apiLevel =
        apiLevelCompute.computeApiLevelForLibraryReference(
            reference.withHolder(
                firstClassWithReferenceOrLibraryClass.getType(), appView.dexItemFactory()),
            ComputedApiLevel.unknown());
    if (apiLevel.isKnownApiLevel()) {
      return Pair.create(firstClassWithReferenceOrLibraryClass, apiLevel);
    }
    // We were unable to find a definition in the class hierarchy, check all interfaces for a
    // definition or the library interfaces for the first interface definition.
    Set<DexClass> firstLibraryInterfaces =
        findAllFirstLibraryInterfacesOrProgramClassWithDefinition(appInfo, holder, reference);
    if (firstLibraryInterfaces.size() == 1) {
      DexClass firstClass = firstLibraryInterfaces.iterator().next();
      if (!firstClass.isLibraryClass()) {
        return Pair.create(firstClass, appView.computedMinApiLevel());
      }
    }
    DexClass foundClass = null;
    ComputedApiLevel minApiLevel = ComputedApiLevel.unknown();
    for (DexClass libraryInterface : firstLibraryInterfaces) {
      assert libraryInterface.isLibraryClass();
      ComputedApiLevel libraryIfaceApiLevel =
          apiLevelCompute.computeApiLevelForLibraryReference(
              reference.withHolder(
                  firstClassWithReferenceOrLibraryClass.getType(), appView.dexItemFactory()),
              ComputedApiLevel.unknown());
      if (minApiLevel.isGreaterThan(libraryIfaceApiLevel)) {
        minApiLevel = libraryIfaceApiLevel;
        foundClass = libraryInterface;
      }
    }
    return Pair.create(foundClass, minApiLevel);
  }

  private static DexClass firstLibraryClassOrProgramClassWithDefinition(
      AppInfoWithClassHierarchy appInfo, DexClass originalClass, DexMember<?, ?> reference) {
    if (originalClass.isLibraryClass()) {
      return originalClass;
    }
    WorkList<DexClass> workList = WorkList.newIdentityWorkList(originalClass);
    while (workList.hasNext()) {
      DexClass clazz = workList.next();
      if (clazz.isLibraryClass()) {
        return clazz;
      } else if (clazz.lookupMember(reference) != null) {
        return clazz;
      } else if (clazz.getSuperType() != null) {
        appInfo
            .contextIndependentDefinitionForWithResolutionResult(clazz.getSuperType())
            .forEachClassResolutionResult(workList::addIfNotSeen);
      }
    }
    return null;
  }

  private static Set<DexClass> findAllFirstLibraryInterfacesOrProgramClassWithDefinition(
      AppInfoWithClassHierarchy appInfo, DexClass originalClass, DexMember<?, ?> reference) {
    Set<DexClass> interfaces = Sets.newLinkedHashSet();
    WorkList<DexClass> workList = WorkList.newIdentityWorkList(originalClass);
    while (workList.hasNext()) {
      DexClass clazz = workList.next();
      if (clazz.isLibraryClass()) {
        if (clazz.isInterface()) {
          interfaces.add(clazz);
        }
      } else if (clazz.lookupMember(reference) != null) {
        return Collections.singleton(clazz);
      } else {
        clazz.forEachImmediateSupertype(
            superType ->
                appInfo
                    .contextIndependentDefinitionForWithResolutionResult(superType)
                    .forEachClassResolutionResult(workList::addIfNotSeen));
      }
    }
    return interfaces;
  }
}
