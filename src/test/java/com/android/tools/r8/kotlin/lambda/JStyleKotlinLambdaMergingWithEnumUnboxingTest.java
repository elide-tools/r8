// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin.lambda;

import static com.android.tools.r8.ToolHelper.getKotlinCompilers;

import com.android.tools.r8.KotlinCompilerTool.KotlinCompiler;
import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.kotlin.lambda.JStyleKotlinLambdaMergingWithEnumUnboxingTest.Main.EnumUnboxingCandidate;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class JStyleKotlinLambdaMergingWithEnumUnboxingTest extends TestBase {

  private final TestParameters parameters;
  private final KotlinCompiler kotlinc;

  @Parameters(name = "{0}, kotlinc: {1}")
  public static List<Object[]> data() {
    return buildParameters(
        TestBase.getTestParameters().withDexRuntimes().withAllApiLevels().build(),
        getKotlinCompilers());
  }

  public JStyleKotlinLambdaMergingWithEnumUnboxingTest(
      TestParameters parameters, KotlinCompiler kotlinc) {
    this.parameters = parameters;
    this.kotlinc = kotlinc;
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addDefaultRuntimeLibrary(parameters)
        .addLibraryFiles(ToolHelper.getKotlinStdlibJar(kotlinc))
        .addKeepMainRule(Main.class)
        .addHorizontallyMergedClassesInspector(
            inspector -> inspector.assertMergedInto(Lambda2.class, Lambda1.class))
        .addEnumUnboxingInspector(inspector -> inspector.assertUnboxed(EnumUnboxingCandidate.class))
        .enableInliningAnnotations()
        .enableNeverClassInliningAnnotations()
        .setMinApi(parameters.getApiLevel())
        .compile()
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutputLines("Lambda1.method()", "Lambda2.method()");
  }

  static class Main {

    @NeverClassInline
    public enum EnumUnboxingCandidate {
      LAMBDA1,
      LAMBDA2
    }

    public static void main(String[] args) {
      accept(createLambda(EnumUnboxingCandidate.LAMBDA1));
      accept(createLambda(EnumUnboxingCandidate.LAMBDA2));
    }

    @NeverInline
    static I createLambda(EnumUnboxingCandidate value) {
      switch (value) {
        case LAMBDA1:
          return new Lambda1();
        case LAMBDA2:
          return new Lambda2();
        default:
          throw new RuntimeException();
      }
    }

    @NeverInline
    static void accept(I instance) {
      instance.method();
    }
  }

  interface I {

    void method();
  }

  @NeverClassInline
  public static final class Lambda1 implements I {

    @NeverInline
    @Override
    public final void method() {
      System.out.println("Lambda1.method()");
    }
  }

  @NeverClassInline
  public static final class Lambda2 implements I {

    @NeverInline
    @Override
    public final void method() {
      System.out.println("Lambda2.method()");
    }
  }
}
