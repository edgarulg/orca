/*
 * Copyright (c) 2019 Schibsted Media Group.
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
package com.netflix.spinnaker.orca.clouddriver.tasks.providers.aws.cloudformation

import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.clouddriver.OortService
import com.netflix.spinnaker.orca.pipeline.model.PipelineExecutionImpl
import com.netflix.spinnaker.orca.pipeline.model.StageExecutionImpl
import retrofit.RetrofitError
import retrofit.client.Response
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class WaitForCloudFormationCompletionTaskSpec extends Specification {

  def oortService = Mock(OortService)

  @Subject
  def waitForCloudFormationCompletionTask = new WaitForCloudFormationCompletionTask(oortService: oortService)

  @Unroll
  def "should succeed if the stack creation is '#status' and if isChangeSet property is '#isChangeSet'"() {
    given:
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    def context = [
      'credentials': 'creds',
      'cloudProvider': 'aws',
      'isChangeSet': isChangeSet,
      'changeSetName': 'changeSetName',
      'kato.tasks': [[resultObjects: [[stackId: 'stackId']]]]
    ]
    def stage = new StageExecutionImpl(pipeline, 'test', 'test', context)
    def stack = [
      stackId: 'stackId',
      stackStatus: status,
      stackStatusReason: statusReason,
      changeSets: [
        [
          name: 'changeSetName',
          status: status,
          statusReason: statusReason
        ]
      ]
    ]

    when:
    def result = waitForCloudFormationCompletionTask.execute(stage)

    then:
    1 * oortService.getCloudFormationStack('stackId') >> stack
    result.status == expectedResult
    result.outputs == stack
    def deleteChangeSet = result.context.get('deleteChangeSet')
    (deleteChangeSet != null && deleteChangeSet) == shouldDeleteChangeset

    where:
    isChangeSet | status            | statusReason                                        | shouldDeleteChangeset || expectedResult
    false       | 'CREATE_COMPLETE' | 'ignored'                                           | false                 || ExecutionStatus.SUCCEEDED
    false       | 'UPDATE_COMPLETE' | 'ignored'                                           | false                 || ExecutionStatus.SUCCEEDED
    false       | 'DELETE_COMPLETE' | 'ignored'                                           | false                 || ExecutionStatus.SUCCEEDED
    true        | 'FAILED'          | 'The submitted information didn\'t contain changes' | true                  || ExecutionStatus.SUCCEEDED
    true        | 'FAILED'          | 'No updates are to be performed.'                   | true                  || ExecutionStatus.SUCCEEDED
    true        | 'CREATE_COMPLETE' | 'ignored'                                           | false                 || ExecutionStatus.SUCCEEDED
  }

  @Unroll
  def "should be running while deploy is in '#status'"() {
    given:
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    def context = [
      'credentials': 'creds',
      'cloudProvider': 'aws',
      'kato.tasks': [[resultObjects: [[stackId: 'stackId']]]]
    ]
    def stage = new StageExecutionImpl(pipeline, 'test', 'test', context)
    def stack = [stackStatus: 'CREATE_IN_PROGRESS']

    when:
    def result = waitForCloudFormationCompletionTask.execute(stage)

    then:
    1 * oortService.getCloudFormationStack('stackId') >> stack
    result.status == expectedResult
    result.outputs.isEmpty()

    where:
    status                        | expectedResult
    'CREATE_IN_PROGRESS'          | ExecutionStatus.RUNNING
    'UPDATE_IN_PROGRESS'          | ExecutionStatus.RUNNING
    'ROLLBACK_IN_PROGRESS'        | ExecutionStatus.RUNNING
    'UPDATE_ROLLBACK_IN_PROGRESS' | ExecutionStatus.RUNNING
    'DELETE_IN_PROGRESS'          | ExecutionStatus.RUNNING
  }

  def "should be running while clouddriver doesn't return the stack (still not cached)"() {
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    def context = [
      'credentials': 'creds',
      'cloudProvider': 'aws',
      'kato.tasks': [[resultObjects: [[stackId: 'stackId']]]]
    ]
    def stage = new StageExecutionImpl(pipeline, 'test', 'test', context)
    def error404 = new SpinnakerHttpException(RetrofitError.httpError("url", new Response("url", 404, "reason", [], null), null, null))

    when:
    def result = waitForCloudFormationCompletionTask.execute(stage)

    then:
    1 * oortService.getCloudFormationStack('stackId') >> { throw error404 }
    result.status == ExecutionStatus.RUNNING
    result.outputs.isEmpty()
  }

  @Unroll
  def "should error on known error states or unknown stack status"() {
    given:
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    def context = [
      'credentials': 'creds',
      'cloudProvider': 'aws',
      'isChangeSet': isChangeSet,
      'changeSetName': 'changeSetName',
      'kato.tasks': [[resultObjects: [[stackId: 'stackId']]]]
    ]
    def stage = new StageExecutionImpl(pipeline, 'test', 'test', context)
    def stack = [
      stackStatus: status,
      stackStatusReason: "Stack failed",
      changeSets: [
        [
          name: 'changeSetName',
          status: status,
          statusReason: "Change set failed"
        ]
      ]
    ]

    when:
    def result = waitForCloudFormationCompletionTask.execute(stage)

    then:
    1 * oortService.getCloudFormationStack('stackId') >> stack
    RuntimeException ex = thrown()
    ex.message.startsWith(expectedMessage)

    where:
    isChangeSet | status              || expectedMessage
    false       | 'UNKNOWN'           || 'Unexpected stack status'
    false       | 'ROLLBACK_COMPLETE' || 'Irrecoverable stack status - Review the error, make changes in template and delete the stack to re-run the pipeline successfully; Reason: Stack failed'
    false       | 'CREATE_FAILED'     || 'Stack failed'
    false       | 'DELETE_FAILED'     || 'Stack failed'
    true        | 'UNKNOWN'           || 'Unexpected stack status'
    true        | 'ROLLBACK_COMPLETE' || 'Irrecoverable stack status - Review the error, make changes in template and delete the stack to re-run the pipeline successfully; Reason: Stack failed'
    true        | 'FAILED'            || 'Change set failed'
  }

  def "should error when clouddriver responds with an error other than 404"() {
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    def context = [
      'credentials': 'creds',
      'cloudProvider': 'aws',
      'kato.tasks': [[resultObjects: [[stackId: 'stackId']]]]
    ]
    def stage = new StageExecutionImpl(pipeline, 'test', 'test', context)
    def error500 = new SpinnakerHttpException(RetrofitError.httpError("url", new Response("url", 500, "reason", [], null), null, null))

    when:
    def result = waitForCloudFormationCompletionTask.execute(stage)

    then:
    1 * oortService.getCloudFormationStack('stackId') >> { throw error500 }
    RuntimeException ex = thrown()
    ex.message == "Status: 500, URL: url, Message: reason"
    result == null
  }

  @Unroll
  def "should get the change set status if it's a change set"() {
    def pipeline = PipelineExecutionImpl.newPipeline('orca')
    def context = [
      'credentials': 'creds',
      'cloudProvider': 'aws',
      'isChangeSet': true,
      'changeSetName': 'changeSetName',
      'kato.tasks': [[resultObjects: [[stackId: 'stackId']]]]
    ]
    def stage = new StageExecutionImpl(pipeline, 'test', 'test', context)
    def stack = [
      stackStatus: 'UPDATE_COMPLETE',
      changeSets: [
        [
          name: 'changeSetName',
          status: status
        ]
      ]
    ]

    when:
    def result = waitForCloudFormationCompletionTask.execute(stage)

    then:
    1 * oortService.getCloudFormationStack('stackId') >> stack
    result.status == expectedResult

    where:
    status                        | expectedResult
    'CREATE_IN_PROGRESS'          | ExecutionStatus.RUNNING
    'CREATE_COMPLETE'             | ExecutionStatus.SUCCEEDED
  }

}
