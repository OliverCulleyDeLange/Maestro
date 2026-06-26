package maestro.cli.report

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import okio.Sink
import okio.buffer
import kotlin.time.DurationUnit

class JUnitTestSuiteReporter(
    private val mapper: ObjectMapper,
    private val testSuiteName: String?,
    private val detailed: Boolean = false,
) : TestSuiteReporter {

    private fun suiteResultToTestSuite(suite: TestExecutionSummary.SuiteResult) = TestSuite(
        name = testSuiteName ?: "Test Suite",
        device = suite.deviceName,
        failures = suite.failures().size,
        time = suite.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
        timestamp = suite.startTime?.let { millisToCurrentLocalDateTime(it) },
        tests = suite.flows.size,
        testCases = suite.flows
            .map { flow ->
                // Combine flow properties and tags into a single properties list
                val allProperties = mutableListOf<TestCaseProperty>()

                // Add custom properties (excluding JUnit-specific reserved keys)
                flow.properties?.filterKeys { it !in JUNIT_RESERVED_PROPERTY_KEYS }?.forEach { (key, value) ->
                    allProperties.add(TestCaseProperty(key, value))
                }

                // Add tags as a comma-separated property
                flow.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    allProperties.add(TestCaseProperty("tags", tags.joinToString(", ")))
                }

                TestCase(
                    id = flow.properties?.get("junitId") ?: flow.name,
                    name = flow.name,
                    classname = flow.properties?.get("junitClassname") ?: flow.name,
                    file = flow.filePath,
                    failure = flow.failure?.let { failure ->
                        Failure(
                            message = failure.message,
                        )
                    },
                    time = flow.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
                    timestamp = flow.startTime?.let { millisToCurrentLocalDateTime(it) },
                    status = flow.status,
                    properties = allProperties.takeIf { it.isNotEmpty() }
                )
            }
    )


    override fun report(
        summary: TestExecutionSummary,
        out: Sink
    ) {
        if (detailed) {
            reportDetailed(summary, out)
            return
        }
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(
                out.buffer().outputStream(),
                TestSuites(
                    suites = summary
                        .suites
                        .map { suiteResultToTestSuite(it) }
                )
            )
    }

    private sealed class Node
    private class Leaf(val step: TestExecutionSummary.StepResult) : Node()
    private class Group(val step: TestExecutionSummary.StepResult, val children: List<Node>) : Node()

    private fun reportDetailed(summary: TestExecutionSummary, out: Sink) {
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='UTF-8'?>\n")
        sb.append("<testsuites>\n")
        summary.suites.forEach { suite -> appendSuite(sb, suite, indent = 1) }
        sb.append("</testsuites>\n")
        out.buffer().use { it.writeUtf8(sb.toString()) }
    }

    private data class Counts(val tests: Int, val failures: Int)

    private fun appendSuite(sb: StringBuilder, suite: TestExecutionSummary.SuiteResult, indent: Int) {
        val pad = "  ".repeat(indent)
        val rendered = suite.flows.map { flow ->
            val nodes = buildNodes(flow.steps)
            Triple(flow, nodes, flowCounts(flow, nodes))
        }
        val total = Counts(rendered.sumOf { it.third.tests }, rendered.sumOf { it.third.failures })

        sb.append(pad).append("<testsuite")
        appendAttr(sb, "name", testSuiteName ?: "Test Suite")
        appendAttr(sb, "device", suite.deviceName)
        appendAttr(sb, "tests", total.tests.toString())
        appendAttr(sb, "failures", total.failures.toString())
        appendAttr(sb, "time", suite.duration?.toDouble(DurationUnit.SECONDS)?.toString())
        appendAttr(sb, "timestamp", suite.startTime?.let { millisToCurrentLocalDateTime(it) })
        sb.append(">\n")
        rendered.forEach { (flow, nodes, counts) -> appendFlow(sb, flow, nodes, counts, indent + 1) }
        sb.append(pad).append("</testsuite>\n")
    }

    private fun appendFlow(
        sb: StringBuilder,
        flow: TestExecutionSummary.FlowResult,
        nodes: List<Node>,
        counts: Counts,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        sb.append(pad).append("<testsuite")
        appendAttr(sb, "name", flow.name)
        appendAttr(sb, "file", flow.filePath)
        appendAttr(sb, "tests", counts.tests.toString())
        appendAttr(sb, "failures", counts.failures.toString())
        appendAttr(sb, "time", flow.duration?.toDouble(DurationUnit.SECONDS)?.toString())
        appendAttr(sb, "timestamp", flow.startTime?.let { millisToCurrentLocalDateTime(it) })
        sb.append(">\n")

        val failureMsg = flow.failure?.message
        if (nodes.isEmpty()) {
            appendLeaf(sb, flow.name, flow.name, flow.status.toString(), null, failureMsg, indent + 1)
        } else {
            appendNodes(sb, nodes, flow.name, failureMsg, indent + 1)
            if (countFailedLeaves(nodes) == 0 && failureMsg != null) {
                appendLeaf(sb, "Flow execution", flow.name, "FAILED", null, failureMsg, indent + 1)
            }
        }
        sb.append(pad).append("</testsuite>\n")
    }

    private fun appendNodes(sb: StringBuilder, nodes: List<Node>, classname: String, failureMsg: String?, indent: Int) {
        nodes.forEach { node ->
            when (node) {
                is Leaf -> appendLeaf(
                    sb,
                    name = node.step.description,
                    classname = classname,
                    status = node.step.status,
                    time = stepTimeSeconds(node.step.duration),
                    failureMsg = if (isFailed(node.step.status)) failureMsg ?: "Step failed" else null,
                    indent = indent,
                )
                is Group -> {
                    val pad = "  ".repeat(indent)
                    sb.append(pad).append("<testsuite")
                    appendAttr(sb, "name", node.step.description)
                    appendAttr(sb, "tests", countLeaves(node.children).toString())
                    appendAttr(sb, "failures", countFailedLeaves(node.children).toString())
                    appendAttr(sb, "time", stepTimeSeconds(node.step.duration))
                    sb.append(">\n")
                    appendNodes(sb, node.children, classname, failureMsg, indent + 1)
                    sb.append(pad).append("</testsuite>\n")
                }
            }
        }
    }

    private fun appendLeaf(
        sb: StringBuilder,
        name: String,
        classname: String,
        status: String,
        time: String?,
        failureMsg: String?,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        sb.append(pad).append("<testcase")
        appendAttr(sb, "name", name)
        appendAttr(sb, "classname", classname)
        appendAttr(sb, "time", time)
        appendAttr(sb, "status", status)
        if (failureMsg != null) {
            sb.append(">\n")
            sb.append("  ".repeat(indent + 1)).append("<failure>").append(escapeText(failureMsg)).append("</failure>\n")
            sb.append(pad).append("</testcase>\n")
        } else {
            sb.append("/>\n")
        }
    }

    private fun flowCounts(flow: TestExecutionSummary.FlowResult, nodes: List<Node>): Counts {
        if (nodes.isEmpty()) {
            val failed = isFailed(flow.status.toString()) || flow.failure != null
            return Counts(1, if (failed) 1 else 0)
        }
        val leaves = countLeaves(nodes)
        val failedLeaves = countFailedLeaves(nodes)
        val synthetic = failedLeaves == 0 && flow.failure?.message != null
        return Counts(leaves + if (synthetic) 1 else 0, failedLeaves + if (synthetic) 1 else 0)
    }

    private fun buildNodes(steps: List<TestExecutionSummary.StepResult>): List<Node> {
        if (steps.isEmpty()) return emptyList()
        return parseNodes(steps, 0, steps.first().depth).first
    }

    private fun parseNodes(
        steps: List<TestExecutionSummary.StepResult>,
        start: Int,
        atDepth: Int,
    ): Pair<List<Node>, Int> {
        val nodes = mutableListOf<Node>()
        var i = start
        while (i < steps.size) {
            val step = steps[i]
            if (step.depth < atDepth) break
            if (i + 1 < steps.size && steps[i + 1].depth > step.depth) {
                val (children, next) = parseNodes(steps, i + 1, step.depth + 1)
                nodes.add(Group(step, children))
                i = next
            } else {
                nodes.add(Leaf(step))
                i++
            }
        }
        return nodes to i
    }

    private fun countLeaves(nodes: List<Node>): Int = nodes.sumOf {
        when (it) {
            is Leaf -> 1
            is Group -> countLeaves(it.children)
        }
    }

    private fun countFailedLeaves(nodes: List<Node>): Int = nodes.sumOf {
        when (it) {
            is Leaf -> if (isFailed(it.step.status)) 1 else 0
            is Group -> countFailedLeaves(it.children)
        }
    }

    private fun isFailed(status: String) = status.equals("FAILED", ignoreCase = true) ||
        status.equals("ERROR", ignoreCase = true)

    private fun stepTimeSeconds(duration: String): String? = when {
        duration == "<1ms" -> null
        duration.endsWith("ms") -> duration.removeSuffix("ms").toDoubleOrNull()?.let { (it / 1000.0).toString() }
        duration.endsWith("s") -> duration.removeSuffix("s")
        else -> null
    }

    private fun appendAttr(sb: StringBuilder, name: String, value: String?) {
        if (value == null) return
        sb.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"')
    }

    private fun escapeText(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(value: String) = escapeText(value)
        .replace("\"", "&quot;")

    @JacksonXmlRootElement(localName = "testsuites")
    private data class TestSuites(
        @JacksonXmlElementWrapper(useWrapping = false)
        @JsonProperty("testsuite")
        val suites: List<TestSuite>,
    )

    @JacksonXmlRootElement(localName = "testsuite")
    private data class TestSuite(
        @JacksonXmlProperty(isAttribute = true) val name: String,
        @JacksonXmlProperty(isAttribute = true) val device: String?,
        @JacksonXmlProperty(isAttribute = true) val tests: Int,
        @JacksonXmlProperty(isAttribute = true) val failures: Int,
        @JacksonXmlProperty(isAttribute = true) val time: String? = null,
        @JacksonXmlProperty(isAttribute = true) val timestamp: String? = null,
        @JacksonXmlElementWrapper(useWrapping = false)
        @JsonProperty("testcase")
        val testCases: List<TestCase>,
    )

    private data class TestCase(
        @JacksonXmlProperty(isAttribute = true) val id: String,
        @JacksonXmlProperty(isAttribute = true) val name: String,
        @JacksonXmlProperty(isAttribute = true) val classname: String,
        @JacksonXmlProperty(isAttribute = true) val file: String? = null,
        @JacksonXmlProperty(isAttribute = true) val time: String? = null,
        @JacksonXmlProperty(isAttribute = true) val timestamp: String? = null,
        @JacksonXmlProperty(isAttribute = true) val status: FlowStatus,
        @JacksonXmlElementWrapper(localName = "properties")
        @JacksonXmlProperty(localName = "property")
        val properties: List<TestCaseProperty>? = null,
        val failure: Failure? = null,
    )

    private data class Failure(
        @JacksonXmlText val message: String,
    )

    private data class TestCaseProperty(
        @JacksonXmlProperty(isAttribute = true) val name: String,
        @JacksonXmlProperty(isAttribute = true) val value: String,
    )

    companion object {

        private val JUNIT_RESERVED_PROPERTY_KEYS = setOf("junitId", "junitClassname")

        fun xml(testSuiteName: String? = null, detailed: Boolean = false) = JUnitTestSuiteReporter(
            mapper = XmlMapper().apply {
                registerModule(KotlinModule.Builder().build())
                setSerializationInclusion(JsonInclude.Include.NON_NULL)
                configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true)
            },
            testSuiteName = testSuiteName,
            detailed = detailed,
        )

    }

}
