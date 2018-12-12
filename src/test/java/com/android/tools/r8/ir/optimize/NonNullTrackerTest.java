// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.GraphLense;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstancePut;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.optimize.nonnull.FieldAccessTest;
import com.android.tools.r8.ir.optimize.nonnull.NonNullAfterArrayAccess;
import com.android.tools.r8.ir.optimize.nonnull.NonNullAfterFieldAccess;
import com.android.tools.r8.ir.optimize.nonnull.NonNullAfterInvoke;
import com.android.tools.r8.ir.optimize.nonnull.NonNullAfterNullCheck;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.google.common.collect.ImmutableSet;
import java.util.function.Consumer;
import org.junit.Test;

public class NonNullTrackerTest extends NonNullTrackerTestBase {

  private void buildAndTest(
      Class<?> testClass,
      MethodSignature signature,
      int expectedNumberOfNonNull,
      Consumer<IRCode> testAugmentedIRCode)
      throws Exception {
    AppInfoWithLiveness appInfo = build(testClass);
    CodeInspector codeInspector = new CodeInspector(appInfo.app);
    DexEncodedMethod foo = codeInspector.clazz(testClass.getName()).method(signature).getMethod();
    IRCode irCode =
        foo.buildIR(appInfo, GraphLense.getIdentityLense(), TEST_OPTIONS, Origin.unknown());
    checkCountOfNonNull(irCode, 0);

    NonNullTracker nonNullTracker = new NonNullTracker(appInfo, ImmutableSet.of());

    nonNullTracker.addNonNull(irCode);
    assertTrue(irCode.isConsistentSSA());
    checkCountOfNonNull(irCode, expectedNumberOfNonNull);

    if (testAugmentedIRCode != null) {
      testAugmentedIRCode.accept(irCode);
    }

    nonNullTracker.cleanupNonNull(irCode);
    assertTrue(irCode.isConsistentSSA());
    checkCountOfNonNull(irCode, 0);
  }

  private static void checkCountOfNonNull(IRCode code, int expectedOccurrences) {
    int count = 0;
    Instruction prev = null, curr = null;
    InstructionIterator it = code.instructionIterator();
    while (it.hasNext()) {
      prev = curr != null && !curr.isGoto() ? curr : prev;
      curr = it.next();
      if (curr.isNonNull()) {
        // Make sure non-null is added to the right place.
        assertTrue(prev == null
            || NonNullTracker.throwsOnNullInput(prev)
            || (prev.isIf() && prev.asIf().isZeroTest())
            || !curr.getBlock().getPredecessors().contains(prev.getBlock()));
        // Make sure non-null is used or inserted for arguments.
        assertTrue(curr.outValue().numberOfAllUsers() > 0
            || curr.asNonNull().src().isArgument());
        count++;
      }
    }
    assertEquals(expectedOccurrences, count);
  }

  private void checkInvokeGetsNonNullReceiver(IRCode irCode) {
    checkInvokeReceiver(irCode, true);
  }

  private void checkInvokeGetsNullReceiver(IRCode irCode) {
    checkInvokeReceiver(irCode, false);
  }

  private void checkInvokeReceiver(IRCode irCode, boolean isNotNull) {
    InstructionIterator it = irCode.instructionIterator();
    boolean metInvokeWithReceiver = false;
    while (it.hasNext()) {
      Instruction instruction = it.nextUntil(Instruction::isInvokeMethodWithReceiver);
      if (instruction == null) {
        break;
      }
      InvokeMethodWithReceiver invoke = instruction.asInvokeMethodWithReceiver();
      if (invoke.isInvokeDirect()
          || !invoke.getInvokedMethod().name.toString().contains("hashCode")) {
        continue;
      }
      metInvokeWithReceiver = true;
      if (isNotNull) {
        assertTrue(invoke.getReceiver().isNeverNull()
            || invoke.getReceiver().definition.isArgument());
      } else {
        assertFalse(invoke.getReceiver().isNeverNull());
      }
    }
    assertTrue(metInvokeWithReceiver);
  }

  @Test
  public void nonNullAfterSafeInvokes() throws Exception {
    MethodSignature foo =
        new MethodSignature("foo", "int", new String[]{"java.lang.String"});
    buildAndTest(NonNullAfterInvoke.class, foo, 1, this::checkInvokeGetsNonNullReceiver);
    MethodSignature bar =
        new MethodSignature("bar", "int", new String[]{"java.lang.String"});
    buildAndTest(NonNullAfterInvoke.class, bar, 2, this::checkInvokeGetsNullReceiver);
  }

  @Test
  public void nonNullAfterSafeArrayAccess() throws Exception {
    MethodSignature foo =
        new MethodSignature("foo", "int", new String[]{"java.lang.String[]"});
    buildAndTest(NonNullAfterArrayAccess.class, foo, 1, null);
  }

  @Test
  public void nonNullAfterSafeArrayLength() throws Exception {
    MethodSignature signature =
        new MethodSignature("arrayLength", "int", new String[]{"java.lang.String[]"});
    buildAndTest(NonNullAfterArrayAccess.class, signature, 1, null);
  }

  @Test
  public void nonNullAfterSafeFieldAccess() throws Exception {
    MethodSignature foo = new MethodSignature("foo", "int",
        new String[]{FieldAccessTest.class.getCanonicalName()});
    buildAndTest(NonNullAfterFieldAccess.class, foo, 1, null);
  }

  @Test
  public void avoidRedundantNonNull() throws Exception {
    MethodSignature signature = new MethodSignature("foo2", "int",
        new String[]{FieldAccessTest.class.getCanonicalName()});
    buildAndTest(NonNullAfterFieldAccess.class, signature, 1, ircode -> {
      // There are two InstancePut instructions of interest.
      int count = 0;
      InstructionIterator it = ircode.instructionIterator();
      while (it.hasNext()) {
        Instruction instruction = it.nextUntil(Instruction::isInstancePut);
        if (instruction == null) {
          break;
        }
        InstancePut iput = instruction.asInstancePut();
        if (count == 0) {
          // First one in the very first line: its value should not be replaced by NonNullMarker
          // because this instruction will happen _before_ non-null.
          assertFalse(iput.value().definition.isNonNull());
        } else if (count == 1) {
          // Second one after a safe invocation, which should use the value added by NonNullMarker.
          assertTrue(iput.object().definition.isNonNull());
        }
        count++;
      }
      assertEquals(2, count);
    });
  }

  @Test
  public void nonNullAfterNullCheck() throws Exception {
    MethodSignature foo =
        new MethodSignature("foo", "int", new String[]{"java.lang.String"});
    buildAndTest(NonNullAfterNullCheck.class, foo, 1, this::checkInvokeGetsNonNullReceiver);
    MethodSignature bar =
        new MethodSignature("bar", "int", new String[]{"java.lang.String"});
    buildAndTest(NonNullAfterNullCheck.class, bar, 1, this::checkInvokeGetsNonNullReceiver);
    MethodSignature baz =
        new MethodSignature("baz", "int", new String[]{"java.lang.String"});
    buildAndTest(NonNullAfterNullCheck.class, baz, 2, this::checkInvokeGetsNullReceiver);
  }
}
