package net.corda.core.transactions

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.JarSignatureTestUtils.createJar
import net.corda.core.JarSignatureTestUtils.generateKey
import net.corda.core.JarSignatureTestUtils.signJar
import net.corda.core.contracts.*
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.delete
import net.corda.core.internal.deleteRecursively
import net.corda.core.internal.div
import net.corda.core.internal.inputStream
import net.corda.finance.contracts.asset.Cash
import net.corda.node.services.api.IdentityServiceInternal
import net.corda.testing.contracts.DummyContract
import net.corda.testing.core.*
import net.corda.testing.internal.rigorousMock
import net.corda.testing.node.MockServices.Companion.makeTestDatabaseAndAttachmentStorageAndMockServices
import org.junit.*
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.PublicKey
import java.util.function.Predicate
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LedgerTransactionQueryTests {
//    companion object {
//        private val DUMMY_NOTARY = TestIdentity(DUMMY_NOTARY_NAME, 20).party
//        private val ALICE_NAME = CordaX500Name("MegaCorp", "London", "GB")
//    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()
    private val keyPair = generateKeyPair()
    private val servicesAndDatabase = makeTestDatabaseAndAttachmentStorageAndMockServices(
            listOf("net.corda.testing.contracts"),
            rigorousMock<IdentityServiceInternal>().also {
                doReturn(ALICE.party).whenever(it).partyFromKey(ALICE.publicKey)
                doReturn(null).whenever(it).partyFromKey(keyPair.public)
            },  ALICE, moreKeys = keyPair)
    private val services = servicesAndDatabase.second
    private val database = servicesAndDatabase.first
    private val storage = services.attachments

    private val identity: Party = services.myInfo.singleIdentity()

    @Before
    fun setup() {
        services.addMockCordapp(DummyContract.PROGRAM_ID)

        val jarName = makeTestContractJar(Cash.PROGRAM_ID)
        val attachmentId = storage.importAttachment(dir.resolve(jarName).inputStream(), "test", null)

        val jarAndSigner = makeTestSignedContractJar(Cash.PROGRAM_ID)
        val signedJar = jarAndSigner.first
        val attachmentId2 = storage.importAttachment(signedJar.inputStream(), "test", null)
    }

    @After
    fun tearDown() {
        database.close()
    }

    interface Commands {
        data class Cmd1(val id: Int) : CommandData, Commands
        data class Cmd2(val id: Int) : CommandData, Commands
        data class Cmd3(val id: Int) : CommandData, Commands // Unused command, required for command not-present checks.
    }

    @BelongsToContract(DummyContract::class)
    private class StringTypeDummyState(val data: String) : ContractState {
        override val participants: List<AbstractParty> = emptyList()
    }

    @BelongsToContract(DummyContract::class)
    private class IntTypeDummyState(val data: Int) : ContractState {
        override val participants: List<AbstractParty> = emptyList()
    }

    private fun makeDummyState(data: Any): ContractState {
        return when (data) {
            is String -> StringTypeDummyState(data)
            is Int -> IntTypeDummyState(data)
            else -> throw IllegalArgumentException()
        }
    }

    private fun makeDummyStateAndRef(data: Any): StateAndRef<*> {
        val dummyState = makeDummyState(data)
        val fakeIssueTx = services.signInitialTransaction(
                TransactionBuilder(notary = DUMMY_NOTARY)
                        .addOutputState(dummyState, DummyContract.PROGRAM_ID)
                        .addCommand(dummyCommand())
        )
        services.recordTransactions(fakeIssueTx)
        val dummyStateRef = StateRef(fakeIssueTx.id, 0)
        return StateAndRef(TransactionState(dummyState, DummyContract.PROGRAM_ID, DUMMY_NOTARY, constraint = AlwaysAcceptAttachmentConstraint), dummyStateRef)
    }

    private fun makeDummyTransaction(): LedgerTransaction {
        val tx = TransactionBuilder(notary = DUMMY_NOTARY)
        for (i in 0..4) {
            tx.addInputState(makeDummyStateAndRef(i))
            tx.addInputState(makeDummyStateAndRef(i.toString()))
            tx.addOutputState(makeDummyState(i), DummyContract.PROGRAM_ID)
            tx.addOutputState(makeDummyState(i.toString()), DummyContract.PROGRAM_ID)
            tx.addCommand(Commands.Cmd1(i), listOf(identity.owningKey))
            tx.addCommand(Commands.Cmd2(i), listOf(identity.owningKey))
        }
        return tx.toLedgerTransaction(services)
    }

    @Test
    fun `Simple InRef Indexer tests`() {
        val ltx = makeDummyTransaction()
        assertEquals(0, ltx.inRef<IntTypeDummyState>(0).state.data.data)
        assertEquals("0", ltx.inRef<StringTypeDummyState>(1).state.data.data)
        assertEquals(3, ltx.inRef<IntTypeDummyState>(6).state.data.data)
        assertEquals("3", ltx.inRef<StringTypeDummyState>(7).state.data.data)
        assertFailsWith<IndexOutOfBoundsException> { ltx.inRef<IntTypeDummyState>(10) }
    }

    @Test
    fun `Simple OutRef Indexer tests`() {
        val ltx = makeDummyTransaction()
        assertEquals(0, ltx.outRef<IntTypeDummyState>(0).state.data.data)
        assertEquals("0", ltx.outRef<StringTypeDummyState>(1).state.data.data)
        assertEquals(3, ltx.outRef<IntTypeDummyState>(6).state.data.data)
        assertEquals("3", ltx.outRef<StringTypeDummyState>(7).state.data.data)
        assertFailsWith<IndexOutOfBoundsException> { ltx.outRef<IntTypeDummyState>(10) }
    }

    @Test
    fun `Simple Input Indexer tests`() {
        val ltx = makeDummyTransaction()
        assertEquals(0, (ltx.getInput(0) as IntTypeDummyState).data)
        assertEquals("0", (ltx.getInput(1) as StringTypeDummyState).data)
        assertEquals(3, (ltx.getInput(6) as IntTypeDummyState).data)
        assertEquals("3", (ltx.getInput(7) as StringTypeDummyState).data)
        assertFailsWith<IndexOutOfBoundsException> { ltx.getInput(10) }
    }

    @Test
    fun `Simple Output Indexer tests`() {
        val ltx = makeDummyTransaction()
        assertEquals(0, (ltx.getOutput(0) as IntTypeDummyState).data)
        assertEquals("0", (ltx.getOutput(1) as StringTypeDummyState).data)
        assertEquals(3, (ltx.getOutput(6) as IntTypeDummyState).data)
        assertEquals("3", (ltx.getOutput(7) as StringTypeDummyState).data)
        assertFailsWith<IndexOutOfBoundsException> { ltx.getOutput(10) }
    }

    @Test
    fun `Simple Command Indexer tests`() {
        val ltx = makeDummyTransaction()
        assertEquals(0, ltx.getCommand<Commands.Cmd1>(0).value.id)
        assertEquals(0, ltx.getCommand<Commands.Cmd2>(1).value.id)
        assertEquals(3, ltx.getCommand<Commands.Cmd1>(6).value.id)
        assertEquals(3, ltx.getCommand<Commands.Cmd2>(7).value.id)
        assertFailsWith<IndexOutOfBoundsException> { ltx.getOutput(10) }
    }

    @Test
    fun `Simple Inputs of type tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.inputsOfType(IntTypeDummyState::class.java)
        assertEquals(5, intStates.size)
        assertEquals(listOf(0, 1, 2, 3, 4), intStates.map { it.data })
        val stringStates = ltx.inputsOfType<StringTypeDummyState>()
        assertEquals(5, stringStates.size)
        assertEquals(listOf("0", "1", "2", "3", "4"), stringStates.map { it.data })
        val notPresentQuery = ltx.inputsOfType(FungibleAsset::class.java)
        assertEquals(emptyList(), notPresentQuery)
    }

    @Test
    fun `Simple InputsRefs of type tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.inRefsOfType(IntTypeDummyState::class.java)
        assertEquals(5, intStates.size)
        assertEquals(listOf(0, 1, 2, 3, 4), intStates.map { it.state.data.data })
        assertEquals(listOf(ltx.inputs[0], ltx.inputs[2], ltx.inputs[4], ltx.inputs[6], ltx.inputs[8]), intStates)
        val stringStates = ltx.inRefsOfType<StringTypeDummyState>()
        assertEquals(5, stringStates.size)
        assertEquals(listOf("0", "1", "2", "3", "4"), stringStates.map { it.state.data.data })
        assertEquals(listOf(ltx.inputs[1], ltx.inputs[3], ltx.inputs[5], ltx.inputs[7], ltx.inputs[9]), stringStates)
    }

    @Test
    fun `Simple Outputs of type tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.outputsOfType(IntTypeDummyState::class.java)
        assertEquals(5, intStates.size)
        assertEquals(listOf(0, 1, 2, 3, 4), intStates.map { it.data })
        val stringStates = ltx.outputsOfType<StringTypeDummyState>()
        assertEquals(5, stringStates.size)
        assertEquals(listOf("0", "1", "2", "3", "4"), stringStates.map { it.data })
        val notPresentQuery = ltx.outputsOfType(FungibleAsset::class.java)
        assertEquals(emptyList(), notPresentQuery)
    }

    @Test
    fun `Simple OutputsRefs of type tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.outRefsOfType(IntTypeDummyState::class.java)
        assertEquals(5, intStates.size)
        assertEquals(listOf(0, 1, 2, 3, 4), intStates.map { it.state.data.data })
        assertEquals(listOf(0, 2, 4, 6, 8), intStates.map { it.ref.index })
        assertTrue(intStates.all { it.ref.txhash == ltx.id })
        val stringStates = ltx.outRefsOfType<StringTypeDummyState>()
        assertEquals(5, stringStates.size)
        assertEquals(listOf("0", "1", "2", "3", "4"), stringStates.map { it.state.data.data })
        assertEquals(listOf(1, 3, 5, 7, 9), stringStates.map { it.ref.index })
        assertTrue(stringStates.all { it.ref.txhash == ltx.id })
    }

    @Test
    fun `Simple Commands of type tests`() {
        val ltx = makeDummyTransaction()
        val intCmd1 = ltx.commandsOfType(Commands.Cmd1::class.java)
        assertEquals(5, intCmd1.size)
        assertEquals(listOf(0, 1, 2, 3, 4), intCmd1.map { it.value.id })
        val intCmd2 = ltx.commandsOfType<Commands.Cmd2>()
        assertEquals(5, intCmd2.size)
        assertEquals(listOf(0, 1, 2, 3, 4), intCmd2.map { it.value.id })
        val notPresentQuery = ltx.commandsOfType(Commands.Cmd3::class.java)
        assertEquals(emptyList(), notPresentQuery)
    }

    @Test
    fun `Filtered Input Tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.filterInputs(IntTypeDummyState::class.java, Predicate { it.data.rem(2) == 0 })
        assertEquals(3, intStates.size)
        assertEquals(listOf(0, 2, 4), intStates.map { it.data })
        val stringStates: List<StringTypeDummyState> = ltx.filterInputs { it.data == "3" }
        assertEquals("3", stringStates.single().data)
    }

    @Test
    fun `Filtered InRef Tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.filterInRefs(IntTypeDummyState::class.java, Predicate { it.data.rem(2) == 0 })
        assertEquals(3, intStates.size)
        assertEquals(listOf(0, 2, 4), intStates.map { it.state.data.data })
        assertEquals(listOf(ltx.inputs[0], ltx.inputs[4], ltx.inputs[8]), intStates)
        val stringStates: List<StateAndRef<StringTypeDummyState>> = ltx.filterInRefs { it.data == "3" }
        assertEquals("3", stringStates.single().state.data.data)
        assertEquals(ltx.inputs[7], stringStates.single())
    }

    @Test
    fun `Filtered Output Tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.filterOutputs(IntTypeDummyState::class.java, Predicate { it.data.rem(2) == 0 })
        assertEquals(3, intStates.size)
        assertEquals(listOf(0, 2, 4), intStates.map { it.data })
        val stringStates: List<StringTypeDummyState> = ltx.filterOutputs { it.data == "3" }
        assertEquals("3", stringStates.single().data)
    }

    @Test
    fun `Filtered OutRef Tests`() {
        val ltx = makeDummyTransaction()
        val intStates = ltx.filterOutRefs(IntTypeDummyState::class.java, Predicate { it.data.rem(2) == 0 })
        assertEquals(3, intStates.size)
        assertEquals(listOf(0, 2, 4), intStates.map { it.state.data.data })
        assertEquals(listOf(0, 4, 8), intStates.map { it.ref.index })
        assertTrue(intStates.all { it.ref.txhash == ltx.id })
        val stringStates: List<StateAndRef<StringTypeDummyState>> = ltx.filterOutRefs { it.data == "3" }
        assertEquals("3", stringStates.single().state.data.data)
        assertEquals(7, stringStates.single().ref.index)
        assertEquals(ltx.id, stringStates.single().ref.txhash)
    }

    @Test
    fun `Filtered Commands Tests`() {
        val ltx = makeDummyTransaction()
        val intCmds1 = ltx.filterCommands(Commands.Cmd1::class.java, Predicate { it.id.rem(2) == 0 })
        assertEquals(3, intCmds1.size)
        assertEquals(listOf(0, 2, 4), intCmds1.map { it.value.id })
        val intCmds2 = ltx.filterCommands<Commands.Cmd2> { it.id == 3 }
        assertEquals(3, intCmds2.single().value.id)
    }

    @Test
    fun `Find Input Tests`() {
        val ltx = makeDummyTransaction()
        val intState = ltx.findInput(IntTypeDummyState::class.java, Predicate { it.data == 4 })
        assertEquals(ltx.getInput(8), intState)
        val stringState: StringTypeDummyState = ltx.findInput { it.data == "3" }
        assertEquals(ltx.getInput(7), stringState)
    }

    @Test
    fun `Find InRef Tests`() {
        val ltx = makeDummyTransaction()
        val intState = ltx.findInRef(IntTypeDummyState::class.java, Predicate { it.data == 4 })
        assertEquals(ltx.inRef(8), intState)
        val stringState: StateAndRef<StringTypeDummyState> = ltx.findInRef { it.data == "3" }
        assertEquals(ltx.inRef(7), stringState)
    }

    @Test
    fun `Find Output Tests`() {
        val ltx = makeDummyTransaction()
        val intState = ltx.findOutput(IntTypeDummyState::class.java, Predicate { it.data == 4 })
        assertEquals(ltx.getOutput(8), intState)
        val stringState: StringTypeDummyState = ltx.findOutput { it.data == "3" }
        assertEquals(ltx.getOutput(7), stringState)
    }

    @Test
    fun `Find OutRef Tests`() {
        val ltx = makeDummyTransaction()
        val intState = ltx.findOutRef(IntTypeDummyState::class.java, Predicate { it.data == 4 })
        assertEquals(ltx.outRef(8), intState)
        val stringState: StateAndRef<StringTypeDummyState> = ltx.findOutRef { it.data == "3" }
        assertEquals(ltx.outRef(7), stringState)
    }

    @Test
    fun `Find Commands Tests`() {
        val ltx = makeDummyTransaction()
        val intCmd1 = ltx.findCommand(Commands.Cmd1::class.java, Predicate { it.id == 2 })
        assertEquals(ltx.getCommand(4), intCmd1)
        val intCmd2 = ltx.findCommand<Commands.Cmd2> { it.id == 3 }
        assertEquals(ltx.getCommand(7), intCmd2)
    }
    companion object {
        private val DUMMY_NOTARY = TestIdentity(DUMMY_NOTARY_NAME, 20).party
        private val ALICE_NAME = CordaX500Name("MegaCorp", "London", "GB")
        private val ALICE = TestIdentity(ALICE_NAME, 20)

        private val dir = Files.createTempDirectory(LedgerTransactionQueryTests::class.simpleName)

        @BeforeClass
        @JvmStatic
        fun beforeClass() {
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            dir.deleteRecursively()
        }

        private fun makeTestJar(output: OutputStream, extraEntries: List<Pair<String, String>> = emptyList()) {
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

        private fun makeTestSignedContractJar(contractName: String): Pair<Path, PublicKey> {
            val alias = "testAlias"
            val pwd = "testPassword"
            dir.generateKey(alias, pwd, ALICE_NAME.toString())
            val jarName = makeTestContractJar(contractName)
            val signer = dir.signJar(jarName, alias, pwd)
            (dir / "_shredder").delete()
            (dir / "_teststore").delete()
            return dir.resolve(jarName) to signer
        }

        private fun makeTestContractJar(contractName: String): String {
            val packages = contractName.split(".")
            val jarName = "testattachment.jar"
            val className = packages.last()
            createTestClass(className, packages.subList(0, packages.size - 1))
            dir.createJar(jarName, "${contractName.replace(".", "/")}.class")
            return jarName
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
            return Paths.get(fileManager.list(StandardLocation.CLASS_OUTPUT, "", setOf(JavaFileObject.Kind.CLASS), true).single().name)
        }
    }
}