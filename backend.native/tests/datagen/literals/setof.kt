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

    // couple of simple checks for the integer constants
    val si = get_static_int_set()
    println(si === get_static_int_set())
    println(si !== setOf(1, 2, 3))
    println(si != HashSet(listOf(1, 2, 3)))
    println(si == HashSet(listOf(1, 2, 3, 4)))
    println(setOf(1, 2, 3, 4, 3, 3, 2) == HashSet(listOf(1, 2, 3, 4)))

    // check that deduplication works
    val set1 = setOf(1, 2, 3)
    val set2 = setOf(1, 2, 3)
    val set3 = setOf(1, 2, 4)
    println(set1 === set2)
    println(set1 !== set3)
    val sset1 = setOf("asd", "zxzc", "asdasd")
    val sset2 = setOf("asd", "zxzc", "asdasd")
    val sset3 = setOf("asd", "zxzc", "")
    println(sset1 === sset2)
    println(sset1 !== sset3)
}

fun get_static_set(): Set<String> {
    return setOf("a", "b", "c")
}

fun get_static_int_set(): Set<Int> {
    return setOf(1, 2, 3, 4)
}
