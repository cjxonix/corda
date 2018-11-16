package net.corda.node

import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.getOrThrow
import net.corda.core.internal.errors.AddressBindingException
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.PortAllocation
import net.corda.testing.driver.driver
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.internal.internalDriver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import java.lang.IllegalStateException
import java.net.InetSocketAddress
import java.net.ServerSocket

class AddressBindingFailureTests {

    companion object {
        private val portAllocation = PortAllocation.Incremental(20_000)
    }

    @Test
    fun `p2p address`() = assertBindExceptionForOverrides { address -> mapOf("p2pAddress" to address.toString()) }

    @Test
    fun `rpc address`() = assertBindExceptionForOverrides { address -> mapOf("rpcSettings" to mapOf("address" to address.toString())) }

    @Test
    fun `rpc admin address`() = assertBindExceptionForOverrides { address -> mapOf("rpcSettings" to mapOf("adminAddress" to address.toString())) }

    @Test
    fun `H2 address`() = assertBindExceptionForOverrides { address -> mapOf("h2Settings" to mapOf("address" to address.toString()), "dataSourceProperties.dataSource.password" to "password") }

    @Test
    fun `notary P2P address`() {
        ServerSocket(0).use { socket ->

            val notaryName = CordaX500Name.parse("O=Notary Cleaning Service, L=Zurich, C=CH")
            val address = InetSocketAddress(socket.localPort).toNetworkHostAndPort()

            assertThatThrownBy {
                driver(DriverParameters(startNodesInProcess = false,
                    notarySpecs = listOf(NotarySpec(notaryName)),
                    notaryCustomOverrides = mapOf("p2pAddress" to address.toString()),
                        portAllocation = portAllocation)
                ) {} }.isInstanceOfSatisfying(IllegalStateException::class.java) { error ->

                assertThat(error.message).contains(notaryName.toString())
                assertThat(error.cause).isInstanceOfSatisfying(AddressBindingException::class.java) { cause ->

                    assertThat(cause.addresses).contains(address)
                }
            }
        }
    }

    private fun assertBindExceptionForOverrides(overrides: (NetworkHostAndPort) -> Map<String, Any?>) {
        ServerSocket(0).use { socket ->
            val address = InetSocketAddress(socket.localPort).toNetworkHostAndPort()
            internalDriver(startNodesInProcess = true, notarySpecs = emptyList(), inMemoryDB = false, portAllocation = portAllocation, checkAddressesToBindToEagerly = false) {
                assertThatThrownBy { startNode(customOverrides = overrides(address)).getOrThrow() }.isInstanceOfSatisfying(AddressBindingException::class.java) { exception ->
                    assertThat(exception.addresses).contains(address).withFailMessage("Expected addresses to contain $address but was ${exception.addresses}.")
                }
            }
        }
    }

    private fun InetSocketAddress.toNetworkHostAndPort() = NetworkHostAndPort(hostName, port)
}