/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.IrBuildingTransformer
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

// Maximum  number of elements allowed in the statically instantiable map.
private const val SMALL_MAP_THRESHOLD = 30
// Maximum  number of elements allowed in the statically instantiable set.
private const val SMALL_SET_THRESHOLD = 30

// Represents an expression which can be statically instantiated in the
// the read-only memory. The concept is very similar to the constant expression
// but offers an extended set of guarantees.
// TODO: Consider embedding this into IR.
internal sealed class StaticExpr

internal data class StaticConst(val backing: IrConst<*>): StaticExpr() {
    init {
        assert(backing.kind == IrConstKind.String)
    }
}

internal data class StaticSet(val keys: List<StaticConst>): StaticExpr() {
    init {
        assert(keys.size <= SMALL_SET_THRESHOLD)
    }
}

internal data class StaticMap(
        val keys: List<StaticConst>,
        val values: List<StaticConst>): StaticExpr() {
    init {
        assert(keys.size == values.size)
        assert(keys.size <= SMALL_MAP_THRESHOLD)
    }
}

// Checks if it's possible to statically evaluate expression and returns it.
// Otherwise returns null.
internal fun tryCreateStaticExpr(expr: IrExpression): StaticExpr? {
    return null
}

// Simple helper which checks is it's possible to statically evaluate expression.
internal fun canStaticallyEvaluate(expr: IrExpression) =
    tryCreateStaticExpr(expr) != null

internal class CompileTimeEvaluateLowering(val context: Context): FileLoweringPass {

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(object: IrBuildingTransformer(context) {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                val descriptor = expression.descriptor.original
                // TODO
                if (descriptor.fqNameSafe.asString() != "kotlin.collections.listOf" || descriptor.valueParameters.size != 1)
                    return expression
                val elementsArr = expression.getValueArgument(0) as? IrVararg
                    ?: return expression

                // The function is kotlin.collections.listOf<T>(vararg args: T).
                // TODO: refer functions more reliably.

                if (elementsArr.elements.any { it is IrSpreadElement }
                        || !elementsArr.elements.all { it is IrConst<*> && it.type.isString() })
                    return expression


                builder.at(expression)

                val typeArgument = expression.getTypeArgument(0)!!
                return builder.irCall(context.ir.symbols.listOfInternal.owner, listOf(typeArgument)).apply {
                    putValueArgument(0, elementsArr)
                }
            }
        })
    }
}