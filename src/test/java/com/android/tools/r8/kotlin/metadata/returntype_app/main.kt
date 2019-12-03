// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.kotlin.metadata.returntype_app

import com.android.tools.r8.kotlin.metadata.returntype_lib.Impl
import com.android.tools.r8.kotlin.metadata.returntype_lib.Itf

class ProgramClass : Impl() {
  override fun foo(): Itf {
    super.foo()
    println("Program::foo")
    return this
  }
}

fun main() {
  val instance = ProgramClass()
  println(instance == instance.foo())
}
