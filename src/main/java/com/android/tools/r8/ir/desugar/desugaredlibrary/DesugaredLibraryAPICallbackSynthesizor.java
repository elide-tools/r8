// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar.desugaredlibrary;

import static com.android.tools.r8.ir.desugar.desugaredlibrary.DesugaredLibraryAPIConverter.generateTrackDesugaredAPIWarnings;
import static com.android.tools.r8.ir.desugar.desugaredlibrary.DesugaredLibraryAPIConverter.isAPIConversionSyntheticType;
import static com.android.tools.r8.ir.desugar.desugaredlibrary.DesugaredLibraryAPIConverter.methodWithVivifiedTypeInSignature;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.desugar.CfPostProcessingDesugaring;
import com.android.tools.r8.ir.desugar.CfPostProcessingDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.desugaredlibrary.DesugaredLibraryWrapperSynthesizerEventConsumer.DesugaredLibraryAPIConverterPostProcessingEventConsumer;
import com.android.tools.r8.ir.synthetic.DesugaredLibraryAPIConversionCfCodeProvider.APIConverterWrapperCfCodeProvider;
import com.android.tools.r8.utils.OptionalBool;
import com.android.tools.r8.utils.WorkList;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public class DesugaredLibraryAPICallbackSynthesizor implements CfPostProcessingDesugaring {

  private final AppView<?> appView;
  private final DexItemFactory factory;

  private final DesugaredLibraryWrapperSynthesizer wrapperSynthesizor;
  private final Set<DexMethod> trackedCallBackAPIs;

  public DesugaredLibraryAPICallbackSynthesizor(AppView<?> appView) {
    this.appView = appView;
    this.factory = appView.dexItemFactory();
    this.wrapperSynthesizor = new DesugaredLibraryWrapperSynthesizer(appView);
    if (appView.options().testing.trackDesugaredAPIConversions) {
      trackedCallBackAPIs = Sets.newConcurrentHashSet();
    } else {
      trackedCallBackAPIs = null;
    }
  }

  // TODO(b/191656218): Consider parallelizing post processing.
  @Override
  public void postProcessingDesugaring(
      Collection<DexProgramClass> programClasses,
      CfPostProcessingDesugaringEventConsumer eventConsumer,
      ExecutorService executorService) {
    assert noPendingWrappersOrConversions();
    for (DexProgramClass clazz : programClasses) {
      if (!appView.isAlreadyLibraryDesugared(clazz)) {
        ArrayList<DexEncodedMethod> callbacks = new ArrayList<>();
        // We note that methods requiring callbacks are library overrides, therefore, they should
        // always be live in R8.
        for (ProgramMethod virtualProgramMethod : clazz.virtualProgramMethods()) {
          if (shouldRegisterCallback(virtualProgramMethod)) {
            if (trackedCallBackAPIs != null) {
              trackedCallBackAPIs.add(virtualProgramMethod.getReference());
            }
            ProgramMethod callback =
                generateCallbackMethod(
                    virtualProgramMethod.getDefinition(),
                    virtualProgramMethod.getHolder(),
                    eventConsumer);
            callbacks.add(callback.getDefinition());
          }
        }
        if (!callbacks.isEmpty()) {
          clazz.addVirtualMethods(callbacks);
        }
      }
    }
    assert noPendingWrappersOrConversions();
    generateTrackingWarnings();
  }

  private boolean noPendingWrappersOrConversions() {
    for (DexProgramClass pendingSyntheticClass :
        appView.getSyntheticItems().getPendingSyntheticClasses()) {
      assert !isAPIConversionSyntheticType(pendingSyntheticClass.type, wrapperSynthesizor, appView);
    }
    return true;
  }

  public boolean shouldRegisterCallback(ProgramMethod method) {
    // Any override of a library method can be called by the library.
    // We duplicate the method to have a vivified type version callable by the library and
    // a type version callable by the program. We need to add the vivified version to the rootset
    // as it is actually overriding a library method (after changing the vivified type to the core
    // library type), but the enqueuer cannot see that.
    // To avoid too much computation we first look if the method would need to be rewritten if
    // it would override a library method, then check if it overrides a library method.
    DexEncodedMethod definition = method.getDefinition();
    if (definition.isPrivateMethod()
        || definition.isStatic()
        || definition.isAbstract()
        || definition.isLibraryMethodOverride().isFalse()) {
      return false;
    }
    if (!appView.rewritePrefix.hasRewrittenTypeInSignature(definition.getProto(), appView)
        || appView
            .options()
            .desugaredLibraryConfiguration
            .getEmulateLibraryInterface()
            .containsKey(method.getHolderType())) {
      return false;
    }
    // In R8 we should be in the enqueuer, therefore we can duplicate a default method and both
    // methods will be desugared.
    // In D8, this happens after interface method desugaring, we cannot introduce new default
    // methods, but we do not need to since this is a library override (invokes will resolve) and
    // all implementors have been enhanced with a forwarding method which will be duplicated.
    if (!appView.enableWholeProgramOptimizations()) {
      if (method.getHolder().isInterface()
          && method.getDefinition().isDefaultMethod()
          && (!appView.options().canUseDefaultAndStaticInterfaceMethods()
              || appView.options().isDesugaredLibraryCompilation())) {
        return false;
      }
    }
    if (!appView.options().desugaredLibraryConfiguration.supportAllCallbacksFromLibrary
        && appView.options().isDesugaredLibraryCompilation()) {
      return false;
    }
    return overridesNonFinalLibraryMethod(method);
  }

  private boolean overridesNonFinalLibraryMethod(ProgramMethod method) {
    // We look up everywhere to see if there is a supertype/interface implementing the method...
    DexProgramClass holder = method.getHolder();
    WorkList<DexType> workList = WorkList.newIdentityWorkList();
    workList.addIfNotSeen(holder.interfaces.values);
    boolean foundOverrideToRewrite = false;
    // There is no methods with desugared types on Object.
    if (holder.superType != factory.objectType) {
      workList.addIfNotSeen(holder.superType);
    }
    while (workList.hasNext()) {
      DexType current = workList.next();
      DexClass dexClass = appView.definitionFor(current);
      if (dexClass == null) {
        continue;
      }
      workList.addIfNotSeen(dexClass.interfaces.values);
      if (dexClass.superType != factory.objectType) {
        workList.addIfNotSeen(dexClass.superType);
      }
      if (!dexClass.isLibraryClass() && !appView.options().isDesugaredLibraryCompilation()) {
        continue;
      }
      if (!shouldGenerateCallbacksForEmulateInterfaceAPIs(dexClass)) {
        continue;
      }
      DexEncodedMethod dexEncodedMethod = dexClass.lookupVirtualMethod(method.getReference());
      if (dexEncodedMethod != null) {
        // In this case, the object will be wrapped.
        if (appView.rewritePrefix.hasRewrittenType(dexClass.type, appView)) {
          return false;
        }
        if (dexEncodedMethod.isFinal()) {
          // We do not introduce overrides of final methods, in this case, the runtime always
          // execute the default behavior in the final method.
          return false;
        }
        foundOverrideToRewrite = true;
      }
    }
    return foundOverrideToRewrite;
  }

  private boolean shouldGenerateCallbacksForEmulateInterfaceAPIs(DexClass dexClass) {
    if (appView.options().desugaredLibraryConfiguration.supportAllCallbacksFromLibrary) {
      return true;
    }
    Map<DexType, DexType> emulateLibraryInterfaces =
        appView.options().desugaredLibraryConfiguration.getEmulateLibraryInterface();
    return !(emulateLibraryInterfaces.containsKey(dexClass.type)
        || emulateLibraryInterfaces.containsValue(dexClass.type));
  }

  private ProgramMethod generateCallbackMethod(
      DexEncodedMethod originalMethod,
      DexProgramClass clazz,
      DesugaredLibraryAPIConverterPostProcessingEventConsumer eventConsumer) {
    DexMethod methodToInstall =
        methodWithVivifiedTypeInSignature(originalMethod.getReference(), clazz.type, appView);
    CfCode cfCode =
        new APIConverterWrapperCfCodeProvider(
                appView,
                originalMethod.getReference(),
                null,
                wrapperSynthesizor,
                clazz.isInterface(),
                eventConsumer)
            .generateCfCode();
    DexEncodedMethod newMethod =
        wrapperSynthesizor.newSynthesizedMethod(methodToInstall, originalMethod, cfCode);
    newMethod.setCode(cfCode, appView);
    if (originalMethod.isLibraryMethodOverride().isTrue()) {
      newMethod.setLibraryMethodOverride(OptionalBool.TRUE);
    }
    ProgramMethod callback = new ProgramMethod(clazz, newMethod);
    if (eventConsumer != null) {
      eventConsumer.acceptAPIConversionCallback(callback);
    } else {
      assert appView.enableWholeProgramOptimizations();
    }
    return callback;
  }

  private void generateTrackingWarnings() {
    generateTrackDesugaredAPIWarnings(trackedCallBackAPIs, "callback ", appView);
  }
}