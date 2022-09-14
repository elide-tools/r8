// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.code;

import static com.android.tools.r8.graph.DexEncodedMethod.asDexClassAndMethodOrNull;

import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.dex.code.DexInstruction;
import com.android.tools.r8.dex.code.DexInvokeDirect;
import com.android.tools.r8.dex.code.DexInvokeDirectRange;
import com.android.tools.r8.dex.code.DexInvokeInterface;
import com.android.tools.r8.dex.code.DexInvokeInterfaceRange;
import com.android.tools.r8.graph.AppView;
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
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.List;

public class InvokeInterface extends InvokeMethodWithReceiver {

  public InvokeInterface(DexMethod target, Value result, List<Value> arguments) {
    super(target, result, arguments);
  }

  @Override
  public boolean getInterfaceBit() {
    return true;
  }

  @Override
  public int opcode() {
    return Opcodes.INVOKE_INTERFACE;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Type getType() {
    return Type.INTERFACE;
  }

  @Override
  protected String getTypeString() {
    return "Interface";
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
            new DexInvokeInterfaceRange(firstRegister, argumentRegisters, getInvokedMethod());
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
            new DexInvokeInterface(
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
    return other.isInvokeInterface() && super.identicalNonValueNonPositionParts(other);
  }

  @Override
  public boolean isInvokeInterface() {
    return true;
  }

  @Override
  public InvokeInterface asInvokeInterface() {
    return this;
  }

  @Override
  public DexClassAndMethod lookupSingleTarget(
      AppView<?> appView, ProgramMethod context, DynamicType dynamicReceiverType) {
    if (!appView.appInfo().hasLiveness()) {
      return null;
    }
    AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();
    DexEncodedMethod result =
        appViewWithLiveness
            .appInfo()
            .lookupSingleVirtualTarget(
                appViewWithLiveness,
                getInvokedMethod(),
                context,
                true,
                appView,
                dynamicReceiverType);
    return asDexClassAndMethodOrNull(result, appView);
  }

  @Override
  public ConstraintWithTarget inliningConstraint(
      InliningConstraints inliningConstraints, ProgramMethod context) {
    return inliningConstraints.forInvokeInterface(getInvokedMethod(), context);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(
        new CfInvoke(org.objectweb.asm.Opcodes.INVOKEINTERFACE, getInvokedMethod(), true), this);
  }

  @Override
  public boolean definitelyTriggersClassInitialization(
      DexType clazz,
      ProgramMethod context,
      AppView<AppInfoWithLiveness> appView,
      Query mode,
      AnalysisAssumption assumption) {
    return ClassInitializationAnalysis.InstructionUtils.forInvokeInterface(
        this, clazz, context, appView, mode, assumption);
  }

  @Override
  void internalRegisterUse(UseRegistry<?> registry, DexClassAndMethod context) {
    registry.registerInvokeInterface(getInvokedMethod());
  }
}
