// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import static com.android.tools.r8.graph.DexEncodedMethod.asDexClassAndMethodOrNull;

import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.dex.code.DexInstruction;
import com.android.tools.r8.dex.code.DexInvokeDirect;
import com.android.tools.r8.dex.code.DexInvokeDirectRange;
import com.android.tools.r8.dex.code.DexInvokeVirtual;
import com.android.tools.r8.dex.code.DexInvokeVirtualRange;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.AnalysisAssumption;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.Query;
import com.android.tools.r8.ir.analysis.type.DynamicType;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.InliningConstraints;
import com.android.tools.r8.lightir.LIRBuilder;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.List;

public class InvokeVirtual extends InvokeMethodWithReceiver {

  public InvokeVirtual(DexMethod target, Value result, List<Value> arguments) {
    super(target, result, arguments);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean getInterfaceBit() {
    return false;
  }

  @Override
  public int opcode() {
    return Opcodes.INVOKE_VIRTUAL;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Type getType() {
    return Type.VIRTUAL;
  }

  @Override
  protected String getTypeString() {
    return "Virtual";
  }

  @Override
  public void buildDex(DexBuilder builder) {
    DexInstruction instruction;
    int argumentRegisters = requiredArgumentRegisters();
    builder.requestOutgoingRegisters(argumentRegisters);
    if (needsRangedInvoke(builder)) {
      assert argumentsConsecutive(builder);
      int firstRegister = argumentRegisterValue(0, builder);
      if (isPrivateMethodInvokedOnSelf(builder)) {
        instruction =
            new DexInvokeDirectRange(firstRegister, argumentRegisters, getInvokedMethod());
      } else {
        instruction =
            new DexInvokeVirtualRange(firstRegister, argumentRegisters, getInvokedMethod());
      }
    } else {
      int[] individualArgumentRegisters = new int[5];
      int argumentRegistersCount = fillArgumentRegisters(builder, individualArgumentRegisters);
      if (isPrivateMethodInvokedOnSelf(builder)) {
        instruction =
            new DexInvokeDirect(
                argumentRegistersCount,
                getInvokedMethod(),
                individualArgumentRegisters[0], // C
                individualArgumentRegisters[1], // D
                individualArgumentRegisters[2], // E
                individualArgumentRegisters[3], // F
                individualArgumentRegisters[4]); // G
      } else {
        instruction =
            new DexInvokeVirtual(
                argumentRegistersCount,
                getInvokedMethod(),
                individualArgumentRegisters[0], // C
                individualArgumentRegisters[1], // D
                individualArgumentRegisters[2], // E
                individualArgumentRegisters[3], // F
                individualArgumentRegisters[4]); // G
      }
    }
    addInvokeAndMoveResult(instruction, builder);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isInvokeVirtual() && super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokeVirtual() {
    return true;
  }

  @Override
  public InvokeVirtual asInvokeVirtual() {
    return this;
  }

  @Override
  public DexClassAndMethod lookupSingleTarget(
      AppView<?> appView, ProgramMethod context, DynamicType dynamicReceiverType) {
    return lookupSingleTarget(appView, context, dynamicReceiverType, getInvokedMethod());
  }

  public static DexClassAndMethod lookupSingleTarget(
      AppView<?> appView,
      ProgramMethod context,
      DynamicType dynamicReceiverType,
      DexMethod method) {
    DexEncodedMethod result = null;
    if (appView.appInfo().hasLiveness()) {
      AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();
      result =
          appViewWithLiveness
              .appInfo()
              .lookupSingleVirtualTarget(
                  appViewWithLiveness, method, context, false, appView, dynamicReceiverType);
    } else {
      // In D8, allow lookupSingleTarget() to be used for finding final library methods. This is
      // used for library modeling.
      DexType holder = method.holder;
      if (holder.isClassType()) {
        DexClass clazz = appView.definitionFor(holder);
        if (clazz != null
            && (clazz.isLibraryClass() || appView.libraryMethodOptimizer().isModeled(clazz.type))) {
          DexEncodedMethod singleTargetCandidate = clazz.lookupMethod(method);
          if (singleTargetCandidate != null
              && (clazz.isFinal() || singleTargetCandidate.isFinal())) {
            result = singleTargetCandidate;
          }
        }
      }
    }
    return asDexClassAndMethodOrNull(result, appView);
  }

  @Override
  public ConstraintWithTarget inliningConstraint(
      InliningConstraints inliningConstraints, ProgramMethod context) {
    return inliningConstraints.forInvokeVirtual(getInvokedMethod(), context);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(
        new CfInvoke(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, getInvokedMethod(), false), this);
  }

  @Override
  public boolean definitelyTriggersClassInitialization(
      DexType clazz,
      ProgramMethod context,
      AppView<AppInfoWithLiveness> appView,
      Query mode,
      AnalysisAssumption assumption) {
    return ClassInitializationAnalysis.InstructionUtils.forInvokeVirtual(
        this, clazz, context, appView, mode, assumption);
  }

  @Override
  void internalRegisterUse(UseRegistry<?> registry, DexClassAndMethod context) {
    registry.registerInvokeVirtual(getInvokedMethod());
  }

  public static class Builder extends InvokeMethod.Builder<Builder, InvokeVirtual> {

    @Override
    public InvokeVirtual build() {
      return amend(new InvokeVirtual(method, outValue, arguments));
    }

    @Override
    public Builder self() {
      return this;
    }
  }

  @Override
  public void buildLIR(LIRBuilder<Value, BasicBlock> builder) {
    builder.addInvokeVirtual(getInvokedMethod(), arguments());
  }
}
