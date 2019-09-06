package org.jetbrains.kotlin.konan.utils

import org.jetbrains.kotlin.builtins.konan.KonanBuiltIns
import org.jetbrains.kotlin.descriptors.konan.KlibModuleDescriptorFactory
import org.jetbrains.kotlin.descriptors.konan.KonanModuleDescriptorFactory
import org.jetbrains.kotlin.descriptors.konan.impl.KonanModuleDescriptorFactoryImpl
import org.jetbrains.kotlin.konan.util.KlibMetadataFactories
import org.jetbrains.kotlin.serialization.konan.KonanDeserializedModuleDescriptorFactory
import org.jetbrains.kotlin.serialization.konan.KonanDeserializedPackageFragmentsFactory
import org.jetbrains.kotlin.serialization.konan.KonanResolvedModuleDescriptorsFactory
import org.jetbrains.kotlin.serialization.konan.impl.KlibMetadataModuleDescriptorFactoryImpl
import org.jetbrains.kotlin.serialization.konan.impl.KonanDeserializedModuleDescriptorFactoryImpl
import org.jetbrains.kotlin.serialization.konan.impl.KonanDeserializedPackageFragmentsFactoryImpl
import org.jetbrains.kotlin.serialization.konan.impl.KonanResolvedModuleDescriptorsFactoryImpl
import org.jetbrains.kotlin.storage.StorageManager

fun createKonanBuiltIns(storageManager: StorageManager) = KonanBuiltIns(storageManager)

/**
 * The default Kotlin/Native factories.
 */
object KonanFactories : KlibMetadataFactories(::createKonanBuiltIns)