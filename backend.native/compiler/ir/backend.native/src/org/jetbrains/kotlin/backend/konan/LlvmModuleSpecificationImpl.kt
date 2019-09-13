/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan

import org.jetbrains.kotlin.backend.konan.descriptors.konanLibrary
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.module
import org.jetbrains.kotlin.konan.library.KonanLibrary

internal class LlvmModuleSpecificationImpl(private val excludedLibraries: Set<KonanLibrary>) : LlvmModuleSpecification {

    override fun importsKotlinDeclarationsFromOtherObjectFiles(): Boolean =
            excludedLibraries.isNotEmpty() // A bit conservative but still valid.

    override fun containsLibrary(library: KonanLibrary): Boolean =
            library !in excludedLibraries

    override fun containsModule(module: IrModuleFragment): Boolean =
            containsModule(module.descriptor)

    override fun containsModule(module: ModuleDescriptor): Boolean =
            module.konanLibrary !in excludedLibraries

    override fun containsDeclaration(declaration: IrDeclaration): Boolean =
            declaration.module.konanLibrary !in excludedLibraries
}
