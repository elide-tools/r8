// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.classinliner.analysis;

class UnknownParameterUsagePerContext extends ParameterUsagePerContext {

  private static final UnknownParameterUsagePerContext INSTANCE =
      new UnknownParameterUsagePerContext();

  private UnknownParameterUsagePerContext() {}

  static UnknownParameterUsagePerContext getInstance() {
    return INSTANCE;
  }

  @Override
  public ParameterUsage get(AnalysisContext context) {
    return ParameterUsage.top();
  }

  @Override
  public boolean isTop() {
    return true;
  }
}
