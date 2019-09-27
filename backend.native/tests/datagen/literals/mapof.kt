/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package datagen.literals.mapof

import kotlin.test.*

fun assertTrue(cond: Boolean) {
    if (!cond)
        println("FAIL")
}

fun assertFalse(cond: Boolean) {
    if (cond)
        println("FAIL")
}

fun assertEquals(value1: Any?, value2: Any?) {
    if (value1 != value2)
        println("FAIL")
}

fun assertNotEquals(value1: Any?, value2: Any?) {
    if (value1 == value2)
        println("FAIL")
}

@Test
fun runTest() {
    val m = get_static_map()
    assertTrue(m === get_static_map())
    assertTrue(m !== mapOf("b" to "c"))

    // check that we adhere to the map interface
    assertEquals(m.toString(), "{a=b, c=d, e=f}")
    assertEquals(m.size, 3)
    assertFalse(m.isEmpty())
    assertTrue(m.containsKey("a"))
    assertFalse(m.containsKey("foo"))
    assertTrue(m.containsValue("b"))
    assertFalse(m.containsValue("a"))
    assertEquals(m.get("a"), "b")
    assertEquals(m.get("foo"), null)
    assertEquals(m.keys.toString(), "[a, c, e]")
    assertEquals(m.values.toString(), "[b, d, f]")

    // check equality implementation
    assertEquals(m, hashMapOf("a" to "b", "c" to "d", "e" to "f"))
    assertEquals(mapOf("c" to "wrong", "a" to "b", "c" to "d"), hashMapOf("a" to "b", "c" to "d"))
    assertNotEquals(mapOf("c" to "wrong", "c" to "d"), hashMapOf("a" to "b", "c" to "d"))

    // check iteration order
    assertEquals(mapOf("a" to "b", "b" to "c").toString(), "{a=b, b=c}")
    assertEquals(mapOf("b" to "c", "a" to "b").toString(), "{b=c, a=b}")
    assertEquals(mapOf("b" to "c", "a" to "b", "b" to "e").toString(), "{a=b, b=e}")

    println("OK")
}

fun get_static_map(): Map<String, String> {
    return mapOf("a" to "b", "c" to "d", "e" to "f")
}
