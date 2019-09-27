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

    // couple of simple checks for the integer constants
    val mi = get_static_int_map()
    assertTrue(mi === get_static_int_map())
    assertTrue(mi !== mapOf(1 to "c"))

    assertEquals(mi, hashMapOf(1 to "b", 2 to "f", 3 to "d"))
    assertNotEquals(mi, hashMapOf(1 to "b", 2 to "f", 3 to "e"))
    assertEquals(mi.toString(), "{1=b, 3=d, 2=f}")
    assertEquals(mapOf(1 to 2, 2 to 3).toString(), "{1=2, 2=3}")
    assertEquals(mapOf(2 to 3, 1 to 2).toString(), "{2=3, 1=2}")
    assertEquals(mapOf(2 to 3, 1 to 2, 2 to 1).toString(), "{1=2, 2=1}")

    // check that deduplication works
    val map1 = mapOf(1 to 2, 2 to 3)
    val map2 = mapOf(1 to 2, 2 to 3)
    val map3 = mapOf(1 to 2, 2 to 4)
    assertTrue(map1 === map2)
    assertTrue(map1 !== map3)

    val smap1 = mapOf("a" to 2, "b" to 3)
    val smap2 = mapOf("a" to 2, "b" to 3)
    val smap3 = mapOf("wrong" to 2, "b" to 3)
    assertTrue(smap1 === smap2)
    assertTrue(smap1 !== smap3)

    println("OK")
}

fun get_static_map(): Map<String, String> {
    return mapOf("a" to "b", "c" to "d", "e" to "f")
}

fun get_static_int_map(): Map<Int, String> {
    return mapOf(1 to "b", 3 to "d", 2 to "f")
}
