// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.lightir;

import com.android.tools.r8.errors.Unimplemented;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.If.Type;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.utils.StringUtils;
import it.unimi.dsi.fastutil.ints.IntList;

public class LIRPrinter extends LIRParsedInstructionCallback {

  private static final String SEPERATOR = "\n";
  private final LIRCode code;
  private final StringBuilder builder = new StringBuilder();

  private final int instructionIndexPadding;
  private final int instructionNamePadding;

  private int valueIndex = 0;
  private LIRInstructionView view;

  public LIRPrinter(LIRCode code) {
    super(code);
    this.code = code;
    instructionIndexPadding =
        Math.max(
            fmtInsnIndex(-code.getArgumentCount()).length(),
            fmtInsnIndex(code.getInstructionCount() - 1).length());
    int maxNameLength = 0;
    for (LIRInstructionView view : code) {
      maxNameLength = Math.max(maxNameLength, LIROpcodes.toString(view.getOpcode()).length());
    }
    instructionNamePadding = maxNameLength;
  }

  @Override
  public int getCurrentValueIndex() {
    return valueIndex;
  }

  private void advanceToNextValueIndex() {
    valueIndex++;
  }

  private String fmtValueIndex(int valueIndex) {
    return "v" + valueIndex;
  }

  private String fmtInsnIndex(int instructionIndex) {
    return instructionIndex < 0 ? "--" : ("" + instructionIndex);
  }

  private void appendValueArguments(IntList arguments) {
    for (int i = 0; i < arguments.size(); i++) {
      builder.append(fmtValueIndex(arguments.getInt(i))).append(' ');
    }
  }

  public String prettyPrint() {
    for (int i = 0; i < code.getArgumentCount(); i++) {
      addInstructionHeader("ARG", 0);
      appendOutValue();
      advanceToNextValueIndex();
    }
    code.forEach(this::onInstructionView);
    return builder.toString();
  }

  @Override
  public void onInstructionView(LIRInstructionView view) {
    this.view = view;
    assert view.getInstructionIndex() == getCurrentInstructionIndex();
    int operandSizeInBytes = view.getRemainingOperandSizeInBytes();
    int instructionSizeInBytes = operandSizeInBytes == 0 ? 1 : 2 + operandSizeInBytes;
    String opcode = LIROpcodes.toString(view.getOpcode());
    addInstructionHeader(opcode, instructionSizeInBytes);
    super.onInstructionView(view);
    advanceToNextValueIndex();
  }

  private void addInstructionHeader(String opcode, int instructionSize) {
    if (getCurrentValueIndex() > 0) {
      builder.append(SEPERATOR);
    }
    StringUtils.appendLeftPadded(
        builder, fmtInsnIndex(getCurrentInstructionIndex()), instructionIndexPadding);
    builder.append(':');
    StringUtils.appendLeftPadded(
        builder, Integer.toString(instructionSize), instructionIndexPadding);
    builder.append(": ");
    StringUtils.appendRightPadded(builder, opcode, instructionNamePadding);
    builder.append(' ');
  }

  @Override
  public void onInstruction() {
    throw new Unimplemented(
        "Printing of instruction missing: " + LIROpcodes.toString(view.getOpcode()));
  }

  private StringBuilder appendOutValue() {
    return builder.append(fmtValueIndex(getCurrentValueIndex())).append(" <- ");
  }

  @Override
  public void onConstNull() {
    appendOutValue().append("null");
  }

  @Override
  public void onConstInt(int value) {
    appendOutValue().append(value);
  }

  @Override
  public void onConstString(DexString string) {
    appendOutValue().append("str(").append(string).append(")");
  }

  @Override
  public void onDiv(NumericType type, int leftValueIndex, int rightValueIndex) {
    appendOutValue()
        .append(fmtValueIndex(leftValueIndex))
        .append(' ')
        .append(fmtValueIndex(rightValueIndex))
        .append(' ')
        .append(type);
  }

  @Override
  public void onIf(Type ifKind, int blockIndex, int valueIndex) {
    builder.append(fmtValueIndex(valueIndex)).append(' ').append(fmtInsnIndex(blockIndex));
  }

  @Override
  public void onGoto(int blockIndex) {
    builder.append(fmtInsnIndex(blockIndex));
  }

  @Override
  public void onFallthrough() {
    // Nothing to append.
  }

  @Override
  public void onMoveException(DexType exceptionType) {
    appendOutValue().append(exceptionType);
  }

  @Override
  public void onDebugLocalWrite(int srcIndex) {
    appendOutValue().append(fmtValueIndex(srcIndex));
  }

  @Override
  public void onInvokeMethodInstruction(DexMethod method, IntList arguments) {
    if (!method.getReturnType().isVoidType()) {
      appendOutValue();
    }
    appendValueArguments(arguments);
    builder.append(method);
  }

  @Override
  public void onFieldInstruction(DexField field) {
    builder.append(field);
  }

  @Override
  public void onStaticGet(DexField field) {
    appendOutValue();
    super.onStaticGet(field);
  }

  @Override
  public void onReturnVoid() {
    // Nothing to append.
  }

  @Override
  public void onArrayLength(int arrayValueIndex) {
    appendOutValue().append(fmtValueIndex(arrayValueIndex));
  }

  @Override
  public void onDebugPosition() {
    // Nothing to append.
  }

  @Override
  public void onPhi(DexType type, IntList operands) {
    appendOutValue();
    appendValueArguments(operands);
    builder.append(type);
  }
}
