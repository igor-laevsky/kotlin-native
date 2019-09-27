/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package datagen.literals.setof

import kotlin.test.*

@Test
fun runTest() {
    val s = get_static_set()
    println(s === get_static_set())
    println(s !== setOf("b", "c"))

    // check that we adhere to the set interface
    println(s.toString())
    println(s.contains("a"))
    println(s.contains("foo"))
    println(s.size)
    println(s.containsAll(listOf("a", "b")))
    println(s.containsAll(listOf("a", "b", "bar")))
    println(s.isEmpty())

    // check equality between static and non-static objects
    println(setOf("a", "a", "b", "c") == HashSet(listOf("a", "b", "c")))
    println(setOf("a", "a", "b", "c") != HashSet(listOf("a", "b", "d")))
    println(setOf("a", "a", "b", "d") != HashSet(listOf("a", "b", "c")))
    println(s == setOf("a", "b", "c"))
    println(s != setOf("a", "e", "c"))

    // Check iteration order
    println(setOf("b", "c", "a").toString() == "[b, c, a]")
    println(setOf("c", "b", "a").toString() == "[c, b, a]")

    // Check larger hash map
    val cs = setOf("1", "2", "3", "4", "5", "1")
    println(cs.contains("5"))
    println(cs.contains("1"))
    println(!cs.contains("10"))
}

fun get_static_set(): Set<String> {
    return setOf("a", "b", "c")
}
