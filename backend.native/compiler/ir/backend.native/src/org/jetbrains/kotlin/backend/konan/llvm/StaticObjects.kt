/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import kotlinx.cinterop.cValuesOf
import llvm.*
import org.jetbrains.kotlin.backend.konan.ir.fqNameForIrSerialization
import org.jetbrains.kotlin.backend.konan.ir.llvmSymbolOrigin
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameForIrSerialization

private fun ConstPointer.add(index: Int): ConstPointer {
    return constPointer(LLVMConstGEP(llvm, cValuesOf(Int32(index).llvm), 1)!!)
}

// Must match OBJECT_TAG_PERMANENT_CONTAINER in C++.
private fun StaticData.permanentTag(typeInfo: ConstPointer): ConstPointer {
    // Only pointer arithmetic via GEP works on constant pointers in LLVM.
    return typeInfo.bitcast(int8TypePtr).add(1).bitcast(kTypeInfoPtr)
}

private fun StaticData.objHeader(typeInfo: ConstPointer): Struct {
    return Struct(runtime.objHeaderType, permanentTag(typeInfo))
}

private fun StaticData.arrayHeader(typeInfo: ConstPointer, length: Int): Struct {
    assert (length >= 0)
    return Struct(runtime.arrayHeaderType, permanentTag(typeInfo), Int32(length))
}

internal fun StaticData.createKotlinStringLiteral(value: String): ConstPointer {
    val name = "kstr:" + value.globalHashBase64
    val elements = value.toCharArray().map(::Char16)

    val objRef = createConstKotlinArray(context.ir.symbols.string.owner, elements)

    val res = createAlias(name, objRef)
    LLVMSetLinkage(res.llvm, LLVMLinkage.LLVMWeakAnyLinkage)

    return res
}

internal fun StaticData.createKotlinConstInt(value: Int): ConstPointer {
    val name = "kint:$value"
    val init = createInitializer(context.irBuiltIns.intClass.owner, Int32(value))
    val global = placeGlobal(name, init)
    global.setConstant(true)

    val objHeaderPtr = global.pointer.getElementPtr(0)
    return createRef(objHeaderPtr)
}

private fun StaticData.createRef(objHeaderPtr: ConstPointer) = objHeaderPtr.bitcast(kObjHeaderPtr)

internal fun StaticData.createConstKotlinArray(arrayClass: IrClass, elements: List<LLVMValueRef>) =
        createConstKotlinArray(arrayClass, elements.map { constValue(it) }).llvm

internal fun StaticData.createConstKotlinArray(arrayClass: IrClass, elements: List<ConstValue>): ConstPointer {
    val typeInfo = arrayClass.typeInfoPtr

    val bodyElementType: LLVMTypeRef = elements.firstOrNull()?.llvmType ?: int8Type
    // (use [0 x i8] as body if there are no elements)
    val arrayBody = ConstArray(bodyElementType, elements)

    val compositeType = structType(runtime.arrayHeaderType, arrayBody.llvmType)

    val global = this.createGlobal(compositeType, "")

    val objHeaderPtr = global.pointer.getElementPtr(0)
    val arrayHeader = arrayHeader(typeInfo, elements.size)

    global.setInitializer(Struct(compositeType, arrayHeader, arrayBody))
    global.setConstant(true)

    return createRef(objHeaderPtr)
}

internal fun StaticData.createConstKotlinObject(type: IrClass, vararg fields: ConstValue): ConstPointer {
    val typeInfo = type.typeInfoPtr
    val objHeader = objHeader(typeInfo)

    val global = this.placeGlobal("", Struct(objHeader, *fields))
    global.setConstant(true)

    val objHeaderPtr = global.pointer.getElementPtr(0)

    return createRef(objHeaderPtr)
}

internal fun StaticData.createConstKotlinClass(
        type: IrClass, fieldValues: Map<String, ConstValue>): ConstPointer {

    val classFields = context.getLayoutBuilder(type).fields.
            map { it.fqNameForIrSerialization.asString() }

    assert(fieldValues.keys.toSet() == classFields.toSet()) {
        "must specify values for every class field and nothing more" }

    // Sort incoming fields according to the order of fields in the class.
    val sorted = linkedMapOf<String, ConstValue>()
    classFields.forEach {
        sorted.put(it, fieldValues[it]!!)
    }

    return createConstKotlinObject(type, *sorted.values.toTypedArray())
}

internal fun StaticData.createInitializer(type: IrClass, vararg fields: ConstValue): ConstValue =
        Struct(objHeader(type.typeInfoPtr), *fields)

/**
 * Creates static instance of `kotlin.collections.ArrayList<elementType>` with given values of fields.
 *
 * @param array value for `array: Array<E>` field.
 * @param length value for `length: Int` field.
 */
internal fun StaticData.createConstArrayList(array: ConstPointer, length: Int): ConstPointer {
    val arrayListClass = context.ir.symbols.arrayList.owner

    val arrayListFqName = arrayListClass.fqNameForIrSerialization
    val arrayListFields = mapOf(
        "$arrayListFqName.array" to array,
        "$arrayListFqName.offset" to Int32(0),
        "$arrayListFqName.length" to Int32(length),
        "$arrayListFqName.backing" to NullPointer(kObjHeader))

    return createConstKotlinClass(arrayListClass, arrayListFields)
}

/**
 * Creates static object adhering to the `kotlin.collections.Map<elementType>`.
 * Iteration order of the resulting map is the same as in the input lists.
 *
 * @param keys to populate the map with.
 * @param vals values corresponding to the given keys. Can be null in case if
 *             this map implements a set.
 */
@UseExperimental(ExperimentalStdlibApi::class)
internal fun StaticData.createConstMap(
        keys: List<ConstValue>, vals: List<ConstValue>?): ConstPointer {

    // This abuses HashMap implementation. We fill hashArray with the
    // pow(2, ceil(log(mapSize))) number of integers circulating from 1 to mapSize.
    // This allows us to not depend on the object hash but degrades the hash map
    // into a linearly searched array.
    // TODO: Create separate Map implementation and use it here instead.

    assert(vals == null || keys.size == vals.size)

    val mapSize = keys.size
    val hashSize = mapSize.takeHighestOneBit() shl 1
    val hashShift = hashSize.countLeadingZeroBits() + 1

    val keysArray = createConstKotlinArray(context.ir.symbols.array.owner, keys)
    val valsArray =
            if (vals == null) {
                NullPointer(kObjHeader)
            } else {
                createConstKotlinArray(context.ir.symbols.array.owner, vals)
            }

    // [1, 1, 1, ...] with mapSize elements
    val presenceArray = createConstKotlinArray(
            context.ir.symbols.intArray.owner,
            IntArray(mapSize){1}.map { Int32(it) })
    // [1, 2, ... mapSize, 1, 2, ...] with hashSize elements
    val hashArray = createConstKotlinArray(
            context.ir.symbols.intArray.owner,
            (0 until hashSize).map { Int32((it % mapSize) + 1) })
    val maxProbeDistance = Int32(mapSize * 2)
    val length = Int32(mapSize)

    val mapClass = context.ir.symbols.hashMap.owner
    val className = mapClass.fqNameForIrSerialization
    val fields = mapOf(
            "$className.keysArray" to keysArray,
            "$className.valuesArray" to valsArray,
            "$className.presenceArray" to presenceArray,
            "$className.hashArray" to hashArray,
            "$className.maxProbeDistance" to maxProbeDistance,
            "$className.length" to length,
            "$className._size" to length,
            "$className.keysView" to NullPointer(kObjHeader),
            "$className.valuesView" to NullPointer(kObjHeader),
            "$className.entriesView" to NullPointer(kObjHeader),
            "$className.hashShift" to Int32(hashShift))

    return createConstKotlinClass(mapClass, fields)
}

/**
 * Creates static object adhering to the `kotlin.collections.Set<elementType>`.
 * Iteration order of the resulting set is the same as in the input list.
 *
 * @param elements to populate the set with.
 */
internal fun StaticData.createConstSet(
        elements: List<ConstValue>): ConstPointer {

    val setClass = context.ir.symbols.hashSet.owner

    val constMap = createConstMap(elements, null)
    val className = setClass.fqNameForIrSerialization
    val fields = mapOf("$className.backing" to constMap)

    return createConstKotlinClass(setClass, fields)
}

internal fun StaticData.createUniqueInstance(
        kind: UniqueKind, bodyType: LLVMTypeRef, typeInfo: ConstPointer): ConstPointer {
    assert (getStructElements(bodyType).size == 1) // ObjHeader only.
    val objHeader = when (kind) {
        UniqueKind.UNIT -> objHeader(typeInfo)
        UniqueKind.EMPTY_ARRAY -> arrayHeader(typeInfo, 0)
    }
    val global = this.placeGlobal(kind.llvmName, objHeader, isExported = true)
    global.setConstant(true)
    return global.pointer
}

internal fun ContextUtils.unique(kind: UniqueKind): ConstPointer {
    val descriptor = when (kind) {
        UniqueKind.UNIT -> context.ir.symbols.unit.owner
        UniqueKind.EMPTY_ARRAY -> context.ir.symbols.array.owner
    }
    return if (isExternal(descriptor)) {
        constPointer(importGlobal(
                kind.llvmName, context.llvm.runtime.objHeaderType, origin = descriptor.llvmSymbolOrigin
        ))
    } else {
        context.llvmDeclarations.forUnique(kind).pointer
    }
}

internal val ContextUtils.theUnitInstanceRef: ConstPointer
    get() = this.unique(UniqueKind.UNIT)