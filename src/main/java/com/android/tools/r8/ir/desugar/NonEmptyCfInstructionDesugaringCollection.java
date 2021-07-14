// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;


import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.desugar.CfClassDesugaringCollection.EmptyCfClassDesugaringCollection;
import com.android.tools.r8.ir.desugar.CfClassDesugaringCollection.NonEmptyCfClassDesugaringCollection;
import com.android.tools.r8.ir.desugar.desugaredlibrary.DesugaredLibraryRetargeter;
import com.android.tools.r8.ir.desugar.desugaredlibrary.RetargetingInfo;
import com.android.tools.r8.ir.desugar.invokespecial.InvokeSpecialToSelfDesugaring;
import com.android.tools.r8.ir.desugar.itf.InterfaceMethodRewriter;
import com.android.tools.r8.ir.desugar.lambda.LambdaInstructionDesugaring;
import com.android.tools.r8.ir.desugar.nest.D8NestBasedAccessDesugaring;
import com.android.tools.r8.ir.desugar.nest.NestBasedAccessDesugaring;
import com.android.tools.r8.ir.desugar.records.RecordRewriter;
import com.android.tools.r8.ir.desugar.stringconcat.StringConcatInstructionDesugaring;
import com.android.tools.r8.ir.desugar.twr.TwrInstructionDesugaring;
import com.android.tools.r8.utils.IntBox;
import com.android.tools.r8.utils.ListUtils;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThrowingConsumer;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class NonEmptyCfInstructionDesugaringCollection extends CfInstructionDesugaringCollection {

  private final AppView<?> appView;
  private final List<CfInstructionDesugaring> desugarings = new ArrayList<>();

  private final NestBasedAccessDesugaring nestBasedAccessDesugaring;
  private final RecordRewriter recordRewriter;
  private final DesugaredLibraryRetargeter desugaredLibraryRetargeter;

  NonEmptyCfInstructionDesugaringCollection(AppView<?> appView) {
    this.appView = appView;
    if (appView.options().desugarState.isOff()) {
      this.nestBasedAccessDesugaring = null;
      this.recordRewriter = null;
      this.desugaredLibraryRetargeter = null;
      return;
    }
    this.nestBasedAccessDesugaring = NestBasedAccessDesugaring.create(appView);
    BackportedMethodRewriter backportedMethodRewriter = null;
    desugaredLibraryRetargeter =
        appView.options().desugaredLibraryConfiguration.getRetargetCoreLibMember().isEmpty()
            ? null
            : new DesugaredLibraryRetargeter(appView);
    if (desugaredLibraryRetargeter != null) {
      desugarings.add(desugaredLibraryRetargeter);
    }
    if (appView.options().enableBackportedMethodRewriting()) {
      backportedMethodRewriter = new BackportedMethodRewriter(appView);
    }
    // Place TWR before Interface desugaring to eliminate potential $closeResource interface calls.
    if (appView.options().enableTryWithResourcesDesugaring()) {
      desugarings.add(new TwrInstructionDesugaring(appView));
    }
    // TODO(b/183998768): Enable interface method rewriter cf to cf also in R8.
    if (appView.options().isInterfaceMethodDesugaringEnabled()
        && !appView.enableWholeProgramOptimizations()) {
      desugarings.add(
          new InterfaceMethodRewriter(
              appView, backportedMethodRewriter, desugaredLibraryRetargeter));
    }
    desugarings.add(new LambdaInstructionDesugaring(appView));
    desugarings.add(new InvokeSpecialToSelfDesugaring(appView));
    desugarings.add(new InvokeToPrivateRewriter());
    desugarings.add(new StringConcatInstructionDesugaring(appView));
    desugarings.add(new BufferCovariantReturnTypeRewriter(appView));
    if (backportedMethodRewriter != null && backportedMethodRewriter.hasBackports()) {
      desugarings.add(backportedMethodRewriter);
    }
    if (nestBasedAccessDesugaring != null) {
      desugarings.add(nestBasedAccessDesugaring);
    }
    this.recordRewriter = RecordRewriter.create(appView);
    if (recordRewriter != null) {
      assert !appView.enableWholeProgramOptimizations() : "To be implemented";
      desugarings.add(recordRewriter);
    }
  }

  static NonEmptyCfInstructionDesugaringCollection createForCfToCfNonDesugar(AppView<?> appView) {
    assert appView.options().desugarState.isOff();
    assert appView.options().isGeneratingClassFiles();
    NonEmptyCfInstructionDesugaringCollection desugaringCollection =
        new NonEmptyCfInstructionDesugaringCollection(appView);
    // TODO(b/145775365): special constructor for cf-to-cf compilations with desugaring disabled.
    //  This should be removed once we can represent invoke-special instructions in the IR.
    desugaringCollection.desugarings.add(new InvokeSpecialToSelfDesugaring(appView));
    return desugaringCollection;
  }

  static NonEmptyCfInstructionDesugaringCollection createForCfToDexNonDesugar(AppView<?> appView) {
    assert appView.options().desugarState.isOff();
    assert appView.options().isGeneratingDex();
    NonEmptyCfInstructionDesugaringCollection desugaringCollection =
        new NonEmptyCfInstructionDesugaringCollection(appView);
    desugaringCollection.desugarings.add(new InvokeSpecialToSelfDesugaring(appView));
    desugaringCollection.desugarings.add(new InvokeToPrivateRewriter());
    return desugaringCollection;
  }

  private void ensureCfCode(ProgramMethod method) {
    if (!method.getDefinition().getCode().isCfCode()) {
      appView
          .options()
          .reporter
          .error(
              new StringDiagnostic(
                  "Unsupported attempt to desugar non-CF code",
                  method.getOrigin(),
                  method.getPosition()));
    }
  }

  @Override
  public void prepare(ProgramMethod method, ProgramAdditions programAdditions) {
    ensureCfCode(method);
    desugarings.forEach(d -> d.prepare(method, programAdditions));
  }

  @Override
  public void scan(ProgramMethod method, CfInstructionDesugaringEventConsumer eventConsumer) {
    ensureCfCode(method);
    desugarings.forEach(d -> d.scan(method, eventConsumer));
  }

  @Override
  public void desugar(
      ProgramMethod method,
      MethodProcessingContext methodProcessingContext,
      CfInstructionDesugaringEventConsumer eventConsumer) {
    ensureCfCode(method);
    CfCode cfCode = method.getDefinition().getCode().asCfCode();

    // Tracking of temporary locals used for instruction desugaring. The desugaring of each
    // instruction is assumed to use locals only for the duration of the instruction, such that any
    // temporarily used locals will be free again at the next instruction to be desugared.
    IntBox maxLocalsForCode = new IntBox(cfCode.getMaxLocals());
    IntBox maxLocalsForInstruction = new IntBox(cfCode.getMaxLocals());

    IntBox maxStackForCode = new IntBox(cfCode.getMaxStack());
    IntBox maxStackForInstruction = new IntBox(cfCode.getMaxStack());

    List<CfInstruction> desugaredInstructions =
        ListUtils.flatMap(
            cfCode.getInstructions(),
            instruction -> {
              Collection<CfInstruction> replacement =
                  desugarInstruction(
                      instruction,
                      maxLocalsForInstruction::getAndIncrement,
                      maxStackForInstruction::getAndIncrement,
                      eventConsumer,
                      method,
                      methodProcessingContext);
              if (replacement != null) {
                // Record if we increased the max number of locals and stack height for the method,
                // and reset the next temporary locals register.
                maxLocalsForCode.setMax(maxLocalsForInstruction.getAndSet(cfCode.getMaxLocals()));
                maxStackForCode.setMax(maxStackForInstruction.getAndSet(cfCode.getMaxStack()));
              } else {
                // The next temporary locals register should be unchanged.
                assert maxLocalsForInstruction.get() == cfCode.getMaxLocals();
                assert maxStackForInstruction.get() == cfCode.getMaxStack();
              }
              return replacement;
            },
            null);
    if (desugaredInstructions != null) {
      assert maxLocalsForCode.get() >= cfCode.getMaxLocals();
      assert maxStackForCode.get() >= cfCode.getMaxStack();
      cfCode.setInstructions(desugaredInstructions);
      cfCode.setMaxLocals(maxLocalsForCode.get());
      cfCode.setMaxStack(maxStackForCode.get());
    } else {
      assert noDesugaringBecauseOfImpreciseDesugaring(method);
    }
  }

  private boolean noDesugaringBecauseOfImpreciseDesugaring(ProgramMethod method) {
    assert desugarings.stream().anyMatch(desugaring -> !desugaring.hasPreciseNeedsDesugaring())
        : "Expected code to be desugared";
    assert needsDesugaring(method);
    boolean foundFalsePositive = false;
    for (CfInstruction instruction :
        method.getDefinition().getCode().asCfCode().getInstructions()) {
      for (CfInstructionDesugaring impreciseDesugaring :
          Iterables.filter(desugarings, desugaring -> !desugaring.hasPreciseNeedsDesugaring())) {
        if (impreciseDesugaring.needsDesugaring(instruction, method)) {
          foundFalsePositive = true;
        }
      }
      for (CfInstructionDesugaring preciseDesugaring :
          Iterables.filter(desugarings, desugaring -> desugaring.hasPreciseNeedsDesugaring())) {
        assert !preciseDesugaring.needsDesugaring(instruction, method);
      }
    }
    assert foundFalsePositive;
    return true;
  }

  @Override
  public CfClassDesugaringCollection createClassDesugaringCollection() {
    if (recordRewriter == null) {
      return new EmptyCfClassDesugaringCollection();
    }
    return new NonEmptyCfClassDesugaringCollection(recordRewriter);
  }

  private Collection<CfInstruction> desugarInstruction(
      CfInstruction instruction,
      FreshLocalProvider freshLocalProvider,
      LocalStackAllocator localStackAllocator,
      CfInstructionDesugaringEventConsumer eventConsumer,
      ProgramMethod context,
      MethodProcessingContext methodProcessingContext) {
    // TODO(b/177810578): Migrate other cf-to-cf based desugaring here.
    Iterator<CfInstructionDesugaring> iterator = desugarings.iterator();
    while (iterator.hasNext()) {
      CfInstructionDesugaring desugaring = iterator.next();
      Collection<CfInstruction> replacement =
          desugaring.desugarInstruction(
              instruction,
              freshLocalProvider,
              localStackAllocator,
              eventConsumer,
              context,
              methodProcessingContext,
              appView.dexItemFactory());
      if (replacement != null) {
        assert desugaring.needsDesugaring(instruction, context);
        assert verifyNoOtherDesugaringNeeded(instruction, context, iterator, desugaring);
        return replacement;
      }
    }
    return null;
  }

  @Override
  public boolean needsDesugaring(ProgramMethod method) {
    if (!method.getDefinition().hasCode()) {
      return false;
    }

    Code code = method.getDefinition().getCode();
    if (code.isDexCode()) {
      return false;
    }

    if (!code.isCfCode()) {
      throw new Unreachable("Unexpected attempt to determine if non-CF code needs desugaring");
    }

    return Iterables.any(
        code.asCfCode().getInstructions(), instruction -> needsDesugaring(instruction, method));
  }

  private boolean needsDesugaring(CfInstruction instruction, ProgramMethod context) {
    return Iterables.any(
        desugarings, desugaring -> desugaring.needsDesugaring(instruction, context));
  }

  private boolean verifyNoOtherDesugaringNeeded(
      CfInstruction instruction,
      ProgramMethod context,
      Iterator<CfInstructionDesugaring> iterator,
      CfInstructionDesugaring appliedDesugaring) {
    iterator.forEachRemaining(
        desugaring -> {
          boolean alsoApplicable = desugaring.needsDesugaring(instruction, context);
          // TODO(b/187913003): As part of precise interface desugaring, make sure the
          //  identification is explicitly non-overlapping and remove the exceptions below.
          assert !alsoApplicable
                  || (appliedDesugaring instanceof InterfaceMethodRewriter
                      && (desugaring instanceof InvokeToPrivateRewriter
                          || desugaring instanceof D8NestBasedAccessDesugaring))
                  || (appliedDesugaring instanceof TwrInstructionDesugaring
                      && desugaring instanceof InterfaceMethodRewriter)
              : "Desugaring of "
                  + instruction
                  + " in method "
                  + context.toSourceString()
                  + " has multiple matches: "
                  + appliedDesugaring.getClass().getName()
                  + " and "
                  + desugaring.getClass().getName();
        });
    return true;
  }

  @Override
  public <T extends Throwable> void withD8NestBasedAccessDesugaring(
      ThrowingConsumer<D8NestBasedAccessDesugaring, T> consumer) throws T {
    if (nestBasedAccessDesugaring != null) {
      assert nestBasedAccessDesugaring instanceof D8NestBasedAccessDesugaring;
      consumer.accept((D8NestBasedAccessDesugaring) nestBasedAccessDesugaring);
    }
  }

  @Override
  public RetargetingInfo getRetargetingInfo() {
    if (desugaredLibraryRetargeter != null) {
      return desugaredLibraryRetargeter.getRetargetingInfo();
    }
    return null;
  }
}
