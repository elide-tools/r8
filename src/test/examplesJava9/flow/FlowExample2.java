// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package flow;

import java.util.concurrent.Flow.Publisher;

public class FlowExample2 extends flowlib.FlowLib {

  public static void main(String[] args) {
    System.out.println(new FlowExample2().getPublisher().getClass().getSimpleName());
  }

  public Publisher<?> getPublisher() {
    return new FlowExample.OneShotPublisher();
  }
}
