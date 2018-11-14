package net.corda.core

import net.corda.core.internal.delete
import net.corda.core.internal.div
import net.corda.testing.core.ALICE_NAME
import net.corda.core.JarSignatureTestUtils.addManifest
import net.corda.core.JarSignatureTestUtils.createJar
import net.corda.core.JarSignatureTestUtils.generateKey
import net.corda.core.JarSignatureTestUtils.signJar
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.PublicKey
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

object ContractJarTestUtils {

    val dir = Files.createTempDirectory(this::class.simpleName) ?: throw IllegalStateException("Unable to create temporary directory")

    fun makeTestJar(output: OutputStream, extraEntries: List<Pair<String, String>> = emptyList()) {
        output.use {
            val jar = JarOutputStream(it)
            jar.putNextEntry(JarEntry("test1.txt"))
            jar.write("This is some useful content".toByteArray())
            jar.closeEntry()
            jar.putNextEntry(JarEntry("test2.txt"))
            jar.write("Some more useful content".toByteArray())
            extraEntries.forEach {
                jar.putNextEntry(JarEntry(it.first))
                jar.write(it.second.toByteArray())
            }
            jar.closeEntry()
        }
    }

    fun makeTestSignedContractJar(contractName: String, version: String = "1.0"): Pair<Path, PublicKey> {
        val alias = "testAlias"
        val pwd = "testPassword"
        dir.generateKey(alias, pwd, ALICE_NAME.toString())
        val jarName = makeTestContractJar(contractName, true, version)
        val signer = dir.signJar(jarName.toAbsolutePath().toString(), alias, pwd)
        (dir / "_shredder").delete()
        (dir / "_teststore").delete()
        return dir.resolve(jarName) to signer
    }

    fun makeTestContractJar(contractName: String, signed: Boolean = false, version: String = "1.0"): Path {
        val packages = contractName.split(".")
        val jarName = "attachment-${packages.last()}-$version-${(if (signed) "signed" else "")}.jar"
        val className = packages.last()
        createTestClass(className, packages.subList(0, packages.size - 1))
        dir.createJar(jarName, "${contractName.replace(".", "/")}.class")
        dir.addManifest(jarName, Pair(Attributes.Name.IMPLEMENTATION_VERSION, version))
        return dir.resolve(jarName)
    }

    private fun createTestClass(className: String, packages: List<String>): Path {
        val newClass = """package ${packages.joinToString(".")};
                import net.corda.core.contracts.*;
                import net.corda.core.transactions.*;

                public class $className implements Contract {
                    @Override
                    public void verify(LedgerTransaction tx) throws IllegalArgumentException {
                    }
                }
            """.trimIndent()
        val compiler = ToolProvider.getSystemJavaCompiler()
        val source = object : SimpleJavaFileObject(URI.create("string:///${packages.joinToString("/")}/${className}.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence {
                return newClass
            }
        }
        val fileManager = compiler.getStandardFileManager(null, null, null)
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(dir.toFile()))

        val compile = compiler.getTask(System.out.writer(), fileManager, null, null, null, listOf(source)).call()
        val outFile = fileManager.getFileForInput(StandardLocation.CLASS_OUTPUT, packages.joinToString("."), "$className.class")
        return Paths.get(outFile.name)
    }

}