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
    /**
     * When true, each flow is emitted as its own <testsuite> and every command in
     * the flow becomes a <testcase> (requires the run to capture steps). When
     * false, the suite is the <testsuite> and each flow is a single <testcase>
     * (the standard JUnit shape).
     */
    private val detailed: Boolean = false,
) : TestSuiteReporter {

    private fun suiteResultToTestSuite(suite: TestExecutionSummary.SuiteResult) = TestSuite(
        name = testSuiteName ?: "Test Suite",
        device = suite.deviceName,
        failures = suite.failures().size,
        time = suite.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
        timestamp = suite.startTime?.let { millisToCurrentLocalDateTime(it) },
        tests = suite.flows.size,
        testCases = suite.flows.map { flow -> flowToTestCase(flow) }
    )

    private fun flowToTestCase(flow: TestExecutionSummary.FlowResult): TestCase {
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

        return TestCase(
            id = flow.properties?.get("junitId") ?: flow.name,
            name = flow.name,
            classname = flow.properties?.get("junitClassname") ?: flow.name,
            file = flow.filePath,
            failure = flow.failure?.let { failure -> Failure(message = failure.message) },
            time = flow.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
            timestamp = flow.startTime?.let { millisToCurrentLocalDateTime(it) },
            status = flow.status.toString(),
            properties = allProperties.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * Detailed mode (#1210): the flow becomes a <testsuite> and each captured
     * command becomes a <testcase>, so consumers can see which step failed.
     * Falls back to a single flow-level <testcase> when no steps were captured
     * (e.g. the flow errored before running a command) so the failure isn't lost.
     */
    private fun flowToStepTestSuite(
        suite: TestExecutionSummary.SuiteResult,
        flow: TestExecutionSummary.FlowResult,
    ): TestSuite {
        if (flow.steps.isEmpty()) {
            return TestSuite(
                name = flow.name,
                device = suite.deviceName,
                tests = 1,
                failures = if (flow.status == FlowStatus.ERROR) 1 else 0,
                time = flow.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
                timestamp = flow.startTime?.let { millisToCurrentLocalDateTime(it) },
                testCases = listOf(flowToTestCase(flow)),
            )
        }

        val stepCases = flow.steps.map { step ->
            val failed = step.status.equals("FAILED", ignoreCase = true)
            TestCase(
                id = step.description,
                name = step.description,
                classname = flow.name,
                time = stepDurationToSeconds(step.duration),
                status = step.status,
                // StepResult carries no message of its own; the flow's failure is
                // the actual error, so attach it to the failing step.
                failure = if (failed) Failure(message = flow.failure?.message ?: "Step failed") else null,
            )
        }

        return TestSuite(
            name = flow.name,
            device = suite.deviceName,
            tests = flow.steps.size,
            failures = flow.steps.count { it.status.equals("FAILED", ignoreCase = true) },
            time = flow.duration?.toDouble(DurationUnit.SECONDS)?.toString(),
            timestamp = flow.startTime?.let { millisToCurrentLocalDateTime(it) },
            testCases = stepCases,
        )
    }

    override fun report(
        summary: TestExecutionSummary,
        out: Sink
    ) {
        val suites = if (detailed) {
            summary.suites.flatMap { suite -> suite.flows.map { flow -> flowToStepTestSuite(suite, flow) } }
        } else {
            summary.suites.map { suiteResultToTestSuite(it) }
        }

        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValue(
                out.buffer().outputStream(),
                TestSuites(suites = suites)
            )
    }

    /** Convert a StepResult display duration ("3.5s", "96ms", "<1ms") to JUnit seconds. */
    private fun stepDurationToSeconds(duration: String): String? = when {
        duration.endsWith("ms") -> duration.removeSuffix("ms").trim().toDoubleOrNull()?.let { (it / 1000.0).toString() }
        duration.endsWith("s") -> duration.removeSuffix("s").trim().toDoubleOrNull()?.toString()
        else -> null
    }

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
        @JacksonXmlProperty(isAttribute = true) val status: String,
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
