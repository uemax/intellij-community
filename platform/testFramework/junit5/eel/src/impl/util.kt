// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("TestOnlyProblems")

package com.intellij.platform.testFramework.junit5.eel.impl

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.core.nio.fs.MultiRoutingFileSystem
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.fs.EelFileSystemApi
import com.intellij.platform.eel.getOrThrow
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.EelProvider
import com.intellij.platform.testFramework.junit5.eel.fixture.IsolatedFileSystem
import com.intellij.platform.testFramework.junit5.eel.impl.nio.EelUnitTestFileSystem
import com.intellij.platform.testFramework.junit5.eel.impl.nio.EelUnitTestFileSystemProvider
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.TestFixtureInitializer
import com.intellij.testFramework.junit5.fixture.extensionPointFixture
import com.intellij.util.containers.with
import com.intellij.util.io.Ksuid
import com.intellij.util.io.delete
import org.junit.jupiter.api.Assumptions
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.spi.FileSystemProvider
import java.util.function.BiFunction
import kotlin.io.path.name

internal const val FAKE_WINDOWS_ROOT = "\\\\dummy-ij-root\\test-eel\\"

internal val currentOs: EelPath.OS
  get() = if (SystemInfo.isWindows) {
    EelPath.OS.WINDOWS
  }
  else {
    EelPath.OS.UNIX
  }

internal fun eelApiByOs(fileSystem: EelUnitTestFileSystem, os: EelPath.OS): EelApi {
  return when (os) {
    EelPath.OS.UNIX -> EelTestPosixApi(fileSystem, fileSystem.fakeLocalRoot)
    EelPath.OS.WINDOWS -> EelTestWindowsApi(fileSystem, fileSystem.fakeLocalRoot)
  }
}

internal class TestEelProvider(val fileSystem: EelUnitTestFileSystem, val descriptor: EelDescriptor, val eelApi: EelApi) : EelProvider {

  override suspend fun getEelApi(path: Path): EelApi? {
    if (path.root.toString() != fileSystem.fakeLocalRoot) {
      return null
    }
    return eelApi
  }

  override fun getEelDescriptor(path: Path): EelDescriptor? {
    if (path.root.toString() != fileSystem.fakeLocalRoot) {
      return null
    }
    return descriptor
  }

  override suspend fun tryInitialize(project: Project) {
    // trivially initialized
  }

}

internal data class IsolatedFileSystemImpl(override val storageRoot: Path, override val eelDescriptor: EelDescriptor, override val eelApi: EelApi) : IsolatedFileSystem

internal fun eelInitializer(os: EelPath.OS): TestFixtureInitializer<IsolatedFileSystem> = TestFixtureInitializer { initialized ->
  checkMultiRoutingFileSystem()
  val meaningfulDirName = "eel-fixture-${os.name.lowercase()}"
  val directory = Files.createTempDirectory(meaningfulDirName)
  val id = directory.toString().substringAfter(meaningfulDirName)

  val fakeRoot = if (SystemInfo.isUnix) {
    "/eel-test/${directory.name}"
  }
  else {
    "\\\\eel-test\\${directory.name}"
  }

  val defaultProvider = FileSystems.getDefault().provider()

  val dirUri = URI("file", id.toString(), null, null)
  val fakeLocalFileSystem = EelUnitTestFileSystem(EelUnitTestFileSystemProvider(defaultProvider), os, directory, fakeRoot)
  val paramMap = mapOf("KEY_ROOT" to fakeRoot,
                       "KEY_PREFIX" to true,
                       "KEY_CASE_SENSITIVE" to (os == EelPath.OS.WINDOWS),
                       "KEY_FUNCTION" to (BiFunction<FileSystemProvider, FileSystem?, FileSystem?> { _, _ -> fakeLocalFileSystem }))
  FileSystems.newFileSystem(URI("file", id.toString(), null, null), paramMap)
  val eelApi = eelApiByOs(fakeLocalFileSystem, os)
  val descriptor = EelTestDescriptor(Ksuid.generate().toString(), eelApi)
  extensionPointFixture(EelProvider.EP_NAME, TestEelProvider(fakeLocalFileSystem, descriptor, eelApi)).init()
  val root = Path.of(fakeRoot)
  initialized(IsolatedFileSystemImpl(root, descriptor, eelApi)) {
    FileSystems.newFileSystem(dirUri, paramMap.with("KEY_FUNCTION", (BiFunction<FileSystemProvider, FileSystem?, FileSystem?> { _, _ -> null })))
    fakeLocalFileSystem.close()
    directory.delete()
  }
}

internal fun eelTempDirectoryFixture(fileSystem: TestFixture<IsolatedFileSystem>): TestFixtureInitializer<Path> = TestFixtureInitializer { initialized ->
  val fsdata = fileSystem.init()
  val eelApi = fsdata.eelApi
  val tempDir = eelApi.fs.createTemporaryDirectory(EelFileSystemApi.CreateTemporaryEntryOptions.Builder().build()).getOrThrow()
  val nioTempDir = eelApi.mapper.toNioPath(tempDir)
  initialized(nioTempDir) {
    Files.delete(nioTempDir)
  }
}


internal fun checkMultiRoutingFileSystem() {
  Assumptions.assumeTrue(FileSystems.getDefault().javaClass.name == MultiRoutingFileSystem::class.java.name,
                         "Please enable `-Djava.nio.file.spi.DefaultFileSystemProvider=com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider`")
}
