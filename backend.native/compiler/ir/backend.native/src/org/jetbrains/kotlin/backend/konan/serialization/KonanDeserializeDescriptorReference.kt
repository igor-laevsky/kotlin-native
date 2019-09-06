package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.backend.common.serialization.DescriptorReferenceDeserializer
import org.jetbrains.kotlin.backend.common.serialization.DescriptorUniqIdAware
import org.jetbrains.kotlin.backend.common.serialization.DeserializedDescriptorUniqIdAware
import org.jetbrains.kotlin.backend.common.serialization.UniqIdKey
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName

class KonanDescriptorReferenceDeserializer(
    currentModule: ModuleDescriptor,
    mangler: KotlinMangler,
    builtIns: IrBuiltIns,
    resolvedForwardDeclarations: MutableMap<UniqId, UniqId>
): DescriptorReferenceDeserializer(currentModule, mangler, builtIns, resolvedForwardDeclarations),
   DescriptorUniqIdAware by KonanDescriptorUniqIdAware
