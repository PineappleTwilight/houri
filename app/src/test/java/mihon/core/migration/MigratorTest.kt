package mihon.core.migration

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class MigratorTest {

    lateinit var migrationCompletedListener: MigrationCompletedListener
    lateinit var migrationContext: MigrationContext
    lateinit var migrationJobFactory: MigrationJobFactory
    lateinit var migrationStrategyFactory: MigrationStrategyFactory

    @BeforeEach
    fun initialize() {
        migrationContext = MigrationContext(false)
        migrationJobFactory = spyk(MigrationJobFactory(migrationContext, CoroutineScope(Dispatchers.Main + Job())))
        migrationCompletedListener = spyk<MigrationCompletedListener>(block = {})
        migrationStrategyFactory = spyk(MigrationStrategyFactory(migrationJobFactory, migrationCompletedListener))
    }

    @Test
    fun initialVersion() = runTest {
        val strategy = migrationStrategyFactory.create(0, 1)
        strategy.shouldBeInstanceOf<InitialMigrationStrategy>()

        val migrations = slot<List<Migration>>()
        val execute = strategy(listOf(Migration.of(Migration.ALWAYS) { true }, Migration.of(2f) { false }))

        execute.await()

        @Suppress("DeferredResultUnused")
        verify { migrationJobFactory.create(capture(migrations)) }
        migrations.captured.size shouldBe 1
        eventually(2.seconds) { verify { migrationCompletedListener() } }
    }

    @Test
    fun sameVersion() = runTest {
        val strategy = migrationStrategyFactory.create(1, 1)
        strategy.shouldBeInstanceOf<NoopMigrationStrategy>()

        val execute = strategy(listOf(Migration.of(Migration.ALWAYS) { true }, Migration.of(2f) { false }))

        val result = execute.await()
        result shouldBe false

        @Suppress("DeferredResultUnused")
        verify(exactly = 0) { migrationJobFactory.create(any()) }
    }

    @Test
    fun noMigrations() = runTest {
        val strategy = migrationStrategyFactory.create(1, 2)
        strategy.shouldBeInstanceOf<VersionRangeMigrationStrategy>()

        val execute = strategy(emptyList())

        val result = execute.await()
        result shouldBe false

        @Suppress("DeferredResultUnused")
        verify(exactly = 0) { migrationJobFactory.create(any()) }
    }

    @Test
    fun smallMigration() = runTest {
        val strategy = migrationStrategyFactory.create(1, 2)
        strategy.shouldBeInstanceOf<VersionRangeMigrationStrategy>()

        val migrations = slot<List<Migration>>()
        val execute = strategy(listOf(Migration.of(Migration.ALWAYS) { true }, Migration.of(2f) { true }))

        execute.await()

        @Suppress("DeferredResultUnused")
        verify { migrationJobFactory.create(capture(migrations)) }
        migrations.captured.size shouldBe 2
        eventually(2.seconds) { verify { migrationCompletedListener() } }
    }

    @Test
    fun largeMigration() = runTest {
        val input = listOf(
            Migration.of(Migration.ALWAYS) { true },
            Migration.of(2f) { true },
            Migration.of(3f) { true },
            Migration.of(4f) { true },
            Migration.of(5f) { true },
            Migration.of(6f) { true },
            Migration.of(7f) { true },
            Migration.of(8f) { true },
            Migration.of(9f) { true },
            Migration.of(10f) { true },
        )

        val strategy = migrationStrategyFactory.create(1, 10)
        strategy.shouldBeInstanceOf<VersionRangeMigrationStrategy>()

        val migrations = slot<List<Migration>>()
        val execute = strategy(input)

        execute.await()

        @Suppress("DeferredResultUnused")
        verify { migrationJobFactory.create(capture(migrations)) }
        migrations.captured.size shouldBe 10
        eventually(2.seconds) { verify { migrationCompletedListener() } }
    }

    @Test
    fun withinRangeMigration() = runTest {
        val strategy = migrationStrategyFactory.create(1, 2)
        strategy.shouldBeInstanceOf<VersionRangeMigrationStrategy>()

        val migrations = slot<List<Migration>>()
        val execute = strategy(
            listOf(
                Migration.of(Migration.ALWAYS) { true },
                Migration.of(2f) { true },
                Migration.of(3f) { false },
            ),
        )

        execute.await()

        @Suppress("DeferredResultUnused")
        verify { migrationJobFactory.create(capture(migrations)) }
        migrations.captured.size shouldBe 2
        eventually(2.seconds) { verify { migrationCompletedListener() } }
    }

    companion object {

        @OptIn(DelicateCoroutinesApi::class)
        val mainThreadSurrogate = newSingleThreadContext("UI thread")

        @BeforeAll
        @JvmStatic
        fun setUp() {
            Dispatchers.setMain(mainThreadSurrogate)
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            Dispatchers.resetMain() // reset the main dispatcher to the original Main dispatcher
            mainThreadSurrogate.close()
        }
    }
}
