// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.errors;

import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;

public class MissingGlobalSyntheticsConsumerDiagnostic implements DesugarDiagnostic {

  private final String generatingReason;

  public MissingGlobalSyntheticsConsumerDiagnostic(String generatingReason) {
    this.generatingReason = generatingReason;
  }

  @Override
  public Origin getOrigin() {
    return Origin.unknown();
  }

  @Override
  public Position getPosition() {
    return Position.UNKNOWN;
  }

  @Override
  public String getDiagnosticMessage() {
    return "Invalid build configuration. "
        + "Attempt to create a global synthetic for '"
        + generatingReason
        + "' without a global-synthetics consumer.";
  }
}
