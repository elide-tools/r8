// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.debuginfo;

import static org.junit.Assert.assertTrue;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.cf.CfVersion;
import com.android.tools.r8.transformers.ClassFileTransformer.MethodPredicate;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

@RunWith(Parameterized.class)
public class NopLineRegression230337727Test extends TestBase implements Opcodes {

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withDefaultRuntimes().withApiLevel(AndroidApiLevel.B).build();
  }

  private final TestParameters parameters;

  public NopLineRegression230337727Test(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void test() throws Exception {
    testForRuntime(parameters)
        .addProgramClassFileData(getTestClassTransformed())
        .run(parameters.getRuntime(), TestClass.class)
        .inspect(
            inspector -> {
              MethodSubject foo =
                  inspector.clazz(TestClass.class).uniqueMethodWithOriginalName("foo");
              assertTrue(
                  foo.getLineNumberTable().getLines().toString(),
                  foo.getLineNumberTable().getLines().contains(11));
            });
  }

  static class TestClass {

    public static boolean boo() {
      return true;
    }

    public static void foo(List<Integer> args) {
      System.nanoTime();
      // Code added by transformer for the kotlin code:
      //  if (boo()) {
      //    l.forEach {}
      //  }
      //  if (boo()) {}
    }

    public static void main(String[] args) {
      foo(Arrays.asList(1, 2, 3));
    }
  }

  private static byte[] getTestClassTransformed() throws Exception {
    // Code generated by kotlin but with frames, locals and checkNotNullParameter removed.
    String holder = binaryName(TestClass.class);
    return transformer(TestClass.class)
        .setVersion(CfVersion.V1_5) // Avoid need of stack frames
        .setMaxs(MethodPredicate.onName("foo"), 2, 7)
        .transformMethodInsnInMethod(
            "foo",
            (opcode, owner, name, descriptor, isInterface, visitor) -> {
              Label label1 = new Label();
              visitor.visitLabel(label1);
              visitor.visitLineNumber(4, label1);
              visitor.visitMethodInsn(INVOKESTATIC, holder, "boo", "()Z", false);
              Label label2 = new Label();
              visitor.visitJumpInsn(IFEQ, label2);
              Label label3 = new Label();
              visitor.visitLabel(label3);
              visitor.visitLineNumber(5, label3);
              visitor.visitVarInsn(ALOAD, 0);
              visitor.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
              visitor.visitVarInsn(ASTORE, 1);
              visitor.visitInsn(ICONST_0);
              visitor.visitVarInsn(ISTORE, 2);
              Label label5 = new Label();
              visitor.visitLabel(label5);
              visitor.visitLineNumber(10, label5);
              visitor.visitVarInsn(ALOAD, 1);
              visitor.visitMethodInsn(
                  INVOKEINTERFACE,
                  "java/lang/Iterable",
                  "iterator",
                  "()Ljava/util/Iterator;",
                  true);
              visitor.visitVarInsn(ASTORE, 3);
              Label label6 = new Label();
              visitor.visitLabel(label6);
              visitor.visitVarInsn(ALOAD, 3);
              visitor.visitMethodInsn(
                  INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
              Label label7 = new Label();
              visitor.visitJumpInsn(IFEQ, label7);
              visitor.visitVarInsn(ALOAD, 3);
              visitor.visitMethodInsn(
                  INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
              visitor.visitVarInsn(ASTORE, 4);
              visitor.visitVarInsn(ALOAD, 4);
              visitor.visitTypeInsn(CHECKCAST, "java/lang/Number");
              visitor.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
              visitor.visitVarInsn(ISTORE, 5);
              visitor.visitInsn(ICONST_0);
              visitor.visitVarInsn(ISTORE, 6);
              Label label10 = new Label();
              visitor.visitLabel(label10);
              visitor.visitLineNumber(5, label10);
              visitor.visitInsn(NOP);
              visitor.visitJumpInsn(GOTO, label6);
              visitor.visitLabel(label7);
              visitor.visitLineNumber(11, label7);
              visitor.visitInsn(NOP);
              visitor.visitLabel(label2);
              visitor.visitLineNumber(7, label2);
              visitor.visitMethodInsn(INVOKESTATIC, holder, "boo", "()Z", false);
              Label label12 = new Label();
              visitor.visitJumpInsn(IFEQ, label12);
              visitor.visitLabel(label12);
              visitor.visitLineNumber(8, label12);
              visitor.visitInsn(RETURN);
            })
        .transform();
  }
}
