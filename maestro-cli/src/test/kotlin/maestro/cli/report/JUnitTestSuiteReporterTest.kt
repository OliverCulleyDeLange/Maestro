package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.jupiter.api.Test

class JUnitTestSuiteReporterTest : TestSuiteReporterTest() {

    @Test
    fun `XML - Test passed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithWarning,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" device="iPhone 15" tests="2" failures="0" time="1915.947" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" file=".maestro/flow_a.yaml" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" file=".maestro/sub/flow_b.yaml" time="1494.749" timestamp="$nowPlus2AsIso" status="WARNING"/>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML - Test failed`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithError,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="2" failures="1" time="552.743" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" file=".maestro/flow_a.yaml" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" file=".maestro/sub/flow_b.yaml" time="131.846" timestamp="$nowPlus2AsIso" status="ERROR">
                      <failure>Error message</failure>
                    </testcase>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML - Custom test suite name is used when present`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml("Custom test suite name")
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithWarning,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Custom test suite name" device="iPhone 15" tests="2" failures="0" time="1915.947" timestamp="$nowAsIso">
                    <testcase id="Flow A" name="Flow A" classname="Flow A" file=".maestro/flow_a.yaml" time="421.573" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Flow B" name="Flow B" classname="Flow B" file=".maestro/sub/flow_b.yaml" time="1494.749" timestamp="$nowPlus2AsIso" status="WARNING"/>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML - Tags and properties are included in output`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
        val sink = Buffer()

        // When
        testee.report(
            summary = testWithTagsAndProperties,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="2" failures="0" time="6.0" timestamp="$nowAsIso">
                    <testcase id="Login Flow" name="Login Flow" classname="Login Flow" file=".maestro/auth/login.yaml" time="2.5" timestamp="$nowPlus1AsIso" status="SUCCESS">
                      <properties>
                        <property name="testCaseId" value="TC-001"/>
                        <property name="xray-test-key" value="PROJ-123"/>
                        <property name="priority" value="P0"/>
                        <property name="tags" value="smoke, critical, auth"/>
                      </properties>
                    </testcase>
                    <testcase id="Checkout Flow" name="Checkout Flow" classname="Checkout Flow" file=".maestro/checkout.yaml" time="3.5" timestamp="$nowPlus2AsIso" status="SUCCESS">
                      <properties>
                        <property name="testCaseId" value="TC-002"/>
                        <property name="testrail-case-id" value="C456"/>
                        <property name="tags" value="regression, e2e"/>
                      </properties>
                    </testcase>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML - file attribute is omitted when filePath is null`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithoutFilePath,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" device="iPhone 15" tests="1" failures="0" time="1.0" timestamp="$nowAsIso">
                    <testcase id="Cloud Flow" name="Cloud Flow" classname="Cloud Flow" time="1.0" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML detailed - flat steps become testcases under a flow testsuite`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml(detailed = true)
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithSteps,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="3" failures="0" time="5.0" timestamp="$nowAsIso">
                    <testsuite name="Flow A" tests="3" failures="0" time="5.0" timestamp="$nowPlus1AsIso">
                      <testcase name="1. Launch app" classname="Flow A" time="1.2" status="COMPLETED"/>
                      <testcase name="2. Tap on button" classname="Flow A" time="0.5" status="COMPLETED"/>
                      <testcase name="3. Assert visible" classname="Flow A" time="0.1" status="COMPLETED"/>
                    </testsuite>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML detailed - failed flat step carries the failure message`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml(detailed = true)
        val sink = Buffer()

        // When
        testee.report(
            summary = testErrorWithSteps,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="4" failures="1" time="3.0" timestamp="$nowAsIso">
                    <testsuite name="Flow B" tests="4" failures="1" time="3.0" timestamp="$nowPlus1AsIso">
                      <testcase name="1. Launch app" classname="Flow B" time="1.5" status="COMPLETED"/>
                      <testcase name="2. Tap on optional element" classname="Flow B" status="WARNED"/>
                      <testcase name="3. Tap on button" classname="Flow B" time="2.0" status="FAILED">
                        <failure>Element not found</failure>
                      </testcase>
                      <testcase name="4. Assert visible" classname="Flow B" time="0.0" status="SKIPPED"/>
                    </testsuite>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML detailed - sub-flow steps are nested testsuites`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml(detailed = true)
        val sink = Buffer()

        // When
        testee.report(
            summary = testSuccessWithNestedSteps,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" device="iPhone 15" tests="4" failures="0" time="5.0" timestamp="$nowAsIso">
                    <testsuite name="Flow A" file=".maestro/flow_a.yaml" tests="4" failures="0" time="5.0" timestamp="$nowPlus1AsIso">
                      <testcase name="1. Launch app" classname="Flow A" time="1.2" status="COMPLETED"/>
                      <testsuite name="2. Run sub-flow" tests="2" failures="0" time="2.0">
                        <testcase name="3. Tap on A" classname="Flow A" time="0.3" status="COMPLETED"/>
                        <testcase name="4. Tap on B" classname="Flow A" time="0.2" status="COMPLETED"/>
                      </testsuite>
                      <testcase name="5. Assert visible" classname="Flow A" time="0.1" status="COMPLETED"/>
                    </testsuite>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML detailed - failure lands on the failing leaf inside a nested sub-flow`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml(detailed = true)
        val sink = Buffer()

        // When
        testee.report(
            summary = testErrorWithNestedSteps,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="3" failures="1" time="3.0" timestamp="$nowAsIso">
                    <testsuite name="Flow B" file=".maestro/flow_b.yaml" tests="3" failures="1" time="3.0" timestamp="$nowPlus1AsIso">
                      <testcase name="1. Launch app" classname="Flow B" time="1.0" status="COMPLETED"/>
                      <testsuite name="2. Run sub-flow" tests="2" failures="1" time="2.0">
                        <testcase name="3. Tap on A" classname="Flow B" time="0.3" status="COMPLETED"/>
                        <testcase name="4. Tap on missing" classname="Flow B" time="1.0" status="FAILED">
                          <failure>Element not found</failure>
                        </testcase>
                      </testsuite>
                    </testsuite>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

    @Test
    fun `XML - Custom id and classname are used when present`() {
        // Given
        val testee = JUnitTestSuiteReporter.xml()
        val sink = Buffer()

        // When
        testee.report(
            summary = testWithCustomIdAndClassname,
            out = sink
        )
        val resultStr = sink.readUtf8()

        // Then
        assertThat(resultStr).isEqualTo(
            """
                <?xml version='1.0' encoding='UTF-8'?>
                <testsuites>
                  <testsuite name="Test Suite" tests="2" failures="0" time="6.0" timestamp="$nowAsIso">
                    <testcase id="TC-LOGIN-001" name="Login Flow" classname="com.example.tests.LoginTest" time="2.5" timestamp="$nowPlus1AsIso" status="SUCCESS"/>
                    <testcase id="Checkout Flow" name="Checkout Flow" classname="Checkout Flow" time="3.5" timestamp="$nowPlus2AsIso" status="SUCCESS"/>
                  </testsuite>
                </testsuites>

            """.trimIndent()
        )
    }

}
