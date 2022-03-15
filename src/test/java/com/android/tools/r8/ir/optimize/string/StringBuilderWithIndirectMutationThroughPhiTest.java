// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.string;

import static com.android.tools.r8.ir.optimize.string.utils.StringBuilderCodeMatchers.isInvokeStringBuilderAppendWithString;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class StringBuilderWithIndirectMutationThroughPhiTest extends TestBase {

  @Parameter(0)
  public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  @Test
  public void test() throws Exception {
    R8TestCompileResult compileResult =
        testForR8(parameters.getBackend())
            .addInnerClasses(getClass())
            .addKeepMainRule(Main.class)
            .setMinApi(parameters.getApiLevel())
            .compile()
            .inspect(
                inspector -> {
                  MethodSubject mainMethodSubject = inspector.clazz(Main.class).mainMethod();
                  // TODO(b/114002137): This should be optimized into having only 3 append calls,
                  //  since append("baz").append("qux") can be optimized into append("bazqux").
                  assertEquals(
                      4,
                      mainMethodSubject
                          .streamInstructions()
                          .filter(isInvokeStringBuilderAppendWithString())
                          .count());
                });
    for (boolean condition : BooleanUtils.values()) {
      compileResult
          .run(parameters.getRuntime(), Main.class, Boolean.toString(condition))
          .applyIf(
              condition,
              runResult -> runResult.assertSuccessWithOutputLines("foobarbazqux", "foobarbazqux"),
              runResult -> runResult.assertSuccessWithOutputLines("foobazqux", "bar"));
    }
  }

  static class Main {

    public static void main(String[] args) {
      boolean condition = Boolean.parseBoolean(args[0]);
      StringBuilder sb0 = new StringBuilder();
      StringBuilder sb1 = condition ? sb0 : new StringBuilder();
      sb0.append("foo");
      sb1.append("bar");
      sb0.append("baz");
      sb0.append("qux");
      System.out.println(sb0.toString());
      System.out.println(sb1.toString());
    }
  }
}
