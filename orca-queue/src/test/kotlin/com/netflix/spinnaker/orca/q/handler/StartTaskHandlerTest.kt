/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.netflix.spinnaker.orca.DefaultStageResolver
import com.netflix.spinnaker.orca.NoOpTaskImplementationResolver
import com.netflix.spinnaker.orca.TaskResolver
import com.netflix.spinnaker.orca.api.pipeline.SkippableTask
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.RUNNING
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.SKIPPED
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType.PIPELINE
import com.netflix.spinnaker.orca.api.test.pipeline
import com.netflix.spinnaker.orca.api.test.stage
import com.netflix.spinnaker.orca.events.TaskComplete
import com.netflix.spinnaker.orca.events.TaskStarted
import com.netflix.spinnaker.orca.pipeline.DefaultStageDefinitionBuilderFactory
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import com.netflix.spinnaker.orca.q.CompleteTask
import com.netflix.spinnaker.orca.q.DummyTask
import com.netflix.spinnaker.orca.q.RunTask
import com.netflix.spinnaker.orca.q.StageDefinitionBuildersProvider
import com.netflix.spinnaker.orca.q.StartTask
import com.netflix.spinnaker.orca.q.TasksProvider
import com.netflix.spinnaker.orca.q.buildTasks
import com.netflix.spinnaker.orca.q.singleTaskStage
import com.netflix.spinnaker.q.Queue
import com.netflix.spinnaker.time.fixedClock
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.argumentCaptor
import com.nhaarman.mockito_kotlin.check
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.doThrow
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.lifecycle.CachingMode.GROUP
import org.jetbrains.spek.subject.SubjectSpek
import org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.Environment

object StartTaskHandlerTest : SubjectSpek<StartTaskHandler>({
  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val publisher: ApplicationEventPublisher = mock()
  val environment: Environment = mock()

  val task: DummyTask = mock {
    on { extensionClass } doReturn DummyTask::class.java
    on { aliases() } doReturn emptyList<String>()
    on { isEnabledPropertyName } doReturn SkippableTask.isEnabledPropertyName("DummyTask")
  }

  val taskResolver = TaskResolver(TasksProvider(listOf(task)))
  val stageResolver = DefaultStageResolver(StageDefinitionBuildersProvider(emptyList()))
  val clock = fixedClock()

  subject(GROUP) {
    StartTaskHandler(queue, repository, ContextParameterProcessor(), DefaultStageDefinitionBuilderFactory(stageResolver), publisher, taskResolver, clock, environment)
  }

  fun resetMocks() = reset(queue, repository, publisher, environment)

  describe("when a task starts") {
    val pipeline = pipeline {
      stage {
        type = singleTaskStage.type
        singleTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
      }
    }
    val message = StartTask(pipeline.type, pipeline.id, "foo", pipeline.stages.first().id, "1")

    beforeGroup {
      whenever(repository.retrieve(eq(PIPELINE), eq(message.executionId), any())) doReturn pipeline
      whenever(environment.getProperty("tasks.dummyTask.enabled", Boolean::class.java, true)) doReturn true
    }

    afterGroup(::resetMocks)

    action("the handler receives a message") {
      subject.handle(message)
    }

    it("marks the task as running") {
      verify(repository).storeStage(
        check {
          it.tasks.first().apply {
            assertThat(status).isEqualTo(RUNNING)
            assertThat(startTime).isEqualTo(clock.millis())
          }
        }
      )
    }

    it("runs the task") {
      verify(queue).push(
        RunTask(
          message.executionType,
          message.executionId,
          "foo",
          message.stageId,
          message.taskId,
          DummyTask::class.java
        )
      )
    }

    it("publishes a TaskStarted event") {
      argumentCaptor<TaskStarted>().apply {
        verify(publisher).publishEvent(capture())
        firstValue.apply {
          assertThat(executionType).isEqualTo(pipeline.type)
          assertThat(executionId).isEqualTo(pipeline.id)
          assertThat(stage.id).isEqualTo(message.stageId)
//          assertThat(task.id).isEqualTo(message.taskId)
        }
      }
    }
  }

  describe("when a skippable task starts") {
    val pipeline = pipeline {
      stage {
        type = singleTaskStage.type
        singleTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
      }
    }
    val message = StartTask(pipeline.type, pipeline.id, "foo", pipeline.stages.first().id, "1")

    beforeGroup {
      whenever(repository.retrieve(eq(PIPELINE), eq(message.executionId), any())) doReturn pipeline
      whenever(environment.getProperty("tasks.dummyTask.enabled", Boolean::class.java, true)) doReturn false
    }

    afterGroup(::resetMocks)

    action("the handler receives a message") {
      subject.handle(message)
    }

    it("marks the task as skipped") {
      verify(repository).storeStage(
        check {
          it.tasks.first().apply {
            assertThat(status).isEqualTo(SKIPPED)
            assertThat(startTime).isEqualTo(null)
          }
        }
      )
    }

    it("completes the task") {
      verify(queue).push(
        CompleteTask(
          message,
          SKIPPED
        )
      )
    }

    it("publishes a TaskComplete event") {
      argumentCaptor<TaskComplete>().apply {
        verify(publisher).publishEvent(capture())
        firstValue.apply {
          assertThat(executionType).isEqualTo(pipeline.type)
          assertThat(executionId).isEqualTo(pipeline.id)
          assertThat(stage.id).isEqualTo(message.stageId)
//          assertThat(task.id).isEqualTo(message.taskId)
        }
      }
    }
  }

  describe("when the execution repository has a problem") {
    val pipeline = pipeline {
      stage {
        type = singleTaskStage.type
        singleTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
      }
    }
    val message = StartTask(pipeline.type, pipeline.id, "foo", pipeline.stages.first().id, "1")

    beforeGroup {
      whenever(repository.retrieve(eq(PIPELINE), eq(message.executionId), any())) doThrow NullPointerException()
    }

    afterGroup(::resetMocks)

    it("propagates any exception") {
      assertThrows(NullPointerException::class.java) {
        subject.handle(message)
      }
    }
  }
})
