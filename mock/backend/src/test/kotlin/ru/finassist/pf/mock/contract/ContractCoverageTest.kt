package ru.finassist.pf.mock.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the contract tests complete against the pinned contract (FIN-123): a new operation or error code in
 * fin-assist/contracts fails here until the fake backend gets a case for it or an exclusion with its issue.
 */
class ContractCoverageTest {

    @Test
    fun `every operation of openapi yaml has a shape case`() {
        val covered = ContractShapeTest.cases.map { it.operationId }.toSet()
        assertEquals("operations of openapi.yaml without a case in ContractShapeTest", emptySet<String>(), Contract.operations.keys - covered)
        assertEquals("cases of operations openapi.yaml does not have", emptySet<String>(), covered - Contract.operations.keys)
    }

    @Test
    fun `every code of the registry has a case or an exclusion`() {
        val http = Contract.registry.filterValues { it != null }.keys
        val covered = ContractErrorCodesTest.cases.map { it.code }.toSet()
        val excluded = Exclusions.codes.map { it.code }.toSet()
        assertEquals("codes with an HTTP status in section 8 of api.md that no case reproduces and no exclusion explains", emptySet<String>(), http - covered - excluded)
        assertEquals("excluded codes that a case reproduces: remove the exclusion", emptySet<String>(), covered.intersect(excluded))
        assertEquals("cases or exclusions of codes the registry does not have", emptySet<String>(), (covered + excluded) - Contract.registry.keys)
    }

    @Test
    fun `every stream-only code of the registry has a stream case or an exclusion`() {
        val streamOnly = Contract.registry.filterValues { it == null }.keys
        val covered = ContractShapeTest.cases.mapNotNull { it.errorEvent }.toSet()
        val excluded = Exclusions.codes.map { it.code }.toSet()
        assertEquals("stream event codes of section 8 without a stream case or an exclusion", emptySet<String>(), streamOnly - covered - excluded)
        assertEquals("excluded stream codes that a case reproduces: remove the exclusion", emptySet<String>(), covered.intersect(excluded))
    }

    @Test
    fun `exclusions name a case that exists, a reason and an issue`() {
        val cases = ContractShapeTest.cases.map { it.name }.toSet()
        Exclusions.schema.forEach { assertTrue("schema exclusion of an unknown case: ${it.case}", it.case in cases) }
        (Exclusions.schema.map { it.reason to it.issue } + Exclusions.codes.map { it.reason to it.issue } + Exclusions.behaviours.map { it.reason to it.issue })
            .forEach { (reason, issue) -> assertTrue("an exclusion without a reason or an issue", reason.isNotBlank() && issue.isNotBlank()) }
    }

    @Test
    fun `every behavioural exclusion is run by a test`() {
        assertEquals(
            "Exclusions.behaviours and the gaps ContractBehaviourTest runs",
            ContractBehaviourTest.GAPS.toSet(), Exclusions.behaviours.map { it.id }.toSet(),
        )
        assertEquals("a gap listed twice in ContractBehaviourTest.GAPS", ContractBehaviourTest.GAPS.size, ContractBehaviourTest.GAPS.toSet().size)
    }

    @Test
    fun `the contract copies are of one commit`() {
        val calc = File("src/test/resources/calc/VERSION").readText().trim()
        assertEquals("calc fixtures and openapi.yaml come from one fin-assist/contracts commit (scripts/sync-contracts.sh)", calc, Contract.version)
    }
}
