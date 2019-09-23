package org.jetbrains.kotlin.konan.utils

import org.jetbrains.kotlin.builtins.konan.KonanBuiltIns
import org.jetbrains.kotlin.konan.util.KlibMetadataFactories
import org.jetbrains.kotlin.storage.StorageManager

fun createKonanBuiltIns(storageManager: StorageManager)
    = KonanBuiltIns(storageManager).also {
        println("KonanBuiltIns() = $it")
    }
/**
 * The default Kotlin/Native factories.
 */
object KonanFactories : KlibMetadataFactories(::createKonanBuiltIns)