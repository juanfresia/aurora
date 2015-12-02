/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.mesos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

import org.apache.aurora.Protobufs;
import org.apache.aurora.codec.ThriftBinaryCodec;
import org.apache.aurora.gen.DockerParameter;
import org.apache.aurora.scheduler.ResourceSlot;
import org.apache.aurora.scheduler.TierManager;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.SchedulerException;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.storage.entities.IDockerContainer;
import org.apache.aurora.scheduler.storage.entities.IDockerParameter;
import org.apache.aurora.scheduler.storage.entities.IJobKey;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.ContainerInfo;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import static java.util.Objects.requireNonNull;

/**
 * A factory to create mesos task objects.
 */
public interface MesosTaskFactory {

  /**
   * Creates a mesos task object.
   *
   * @param task Assigned task to translate into a task object.
   * @param slaveId Id of the slave the task is being assigned to.
   * @return A new task.
   * @throws SchedulerException If the task could not be encoded.
   */
  TaskInfo createFrom(IAssignedTask task, SlaveID slaveId) throws SchedulerException;

  // TODO(wfarner): Move this class to its own file to reduce visibility to package private.
  class MesosTaskFactoryImpl implements MesosTaskFactory {
    private static final Logger LOG = Logger.getLogger(MesosTaskFactoryImpl.class.getName());
    private static final String EXECUTOR_PREFIX = "thermos-";

    private final ExecutorSettings executorSettings;
    private final TierManager tierManager;

    @Inject
    MesosTaskFactoryImpl(ExecutorSettings executorSettings, TierManager tierManager) {
      this.executorSettings = requireNonNull(executorSettings);
      this.tierManager = requireNonNull(tierManager);
    }

    @VisibleForTesting
    static ExecutorID getExecutorId(String taskId) {
      return ExecutorID.newBuilder().setValue(EXECUTOR_PREFIX + taskId).build();
    }

    private static String getJobSourceName(IJobKey jobkey) {
      return String.format("%s.%s.%s", jobkey.getRole(), jobkey.getEnvironment(), jobkey.getName());
    }

    private static String getJobSourceName(ITaskConfig task) {
      return getJobSourceName(task.getJob());
    }

    @VisibleForTesting
    static String getInstanceSourceName(ITaskConfig task, int instanceId) {
      return String.format("%s.%s", getJobSourceName(task), instanceId);
    }

    @Override
    public TaskInfo createFrom(IAssignedTask task, SlaveID slaveId) throws SchedulerException {
      requireNonNull(task);
      requireNonNull(slaveId);

      byte[] taskInBytes;
      try {
        taskInBytes = ThriftBinaryCodec.encode(task.newBuilder());
      } catch (ThriftBinaryCodec.CodingException e) {
        LOG.log(Level.SEVERE, "Unable to serialize task.", e);
        throw new SchedulerException("Internal error.", e);
      }

      ITaskConfig config = task.getTask();

      // TODO(wfarner): Re-evaluate if/why we need to continue handling unset assignedPorts field.
      List<Resource> resources = ResourceSlot.from(config).toResourceList(
          task.isSetAssignedPorts()
              ? ImmutableSet.copyOf(task.getAssignedPorts().values())
              : ImmutableSet.of(),
          tierManager.getTier(task.getTask()));

      if (LOG.isLoggable(Level.FINE)) {
        LOG.fine("Setting task resources to "
            + Iterables.transform(resources, Protobufs::toString));
      }
      TaskInfo.Builder taskBuilder =
          TaskInfo.newBuilder()
              .setName(JobKeys.canonicalString(Tasks.getJob(task)))
              .setTaskId(TaskID.newBuilder().setValue(task.getTaskId()))
              .setSlaveId(slaveId)
              .addAllResources(resources)
              .setData(ByteString.copyFrom(taskInBytes));

      if (config.getContainer().isSetMesos()) {
        configureTaskForNoContainer(task, config, taskBuilder);
      } else if (config.getContainer().isSetDocker()) {
        configureTaskForDockerContainer(task, config, taskBuilder);
        return taskBuilder.build();
      } else {
        throw new SchedulerException("Task had no supported container set.");
      }

      return ResourceSlot.matchResourceTypes(taskBuilder.build());
    }

    private void configureTaskForNoContainer(
        IAssignedTask task,
        ITaskConfig config,
        TaskInfo.Builder taskBuilder) {

      taskBuilder.setExecutor(configureTaskForExecutor(task, config).build());
    }

    private void configureTaskForDockerContainer(
        IAssignedTask task,
        ITaskConfig taskConfig,
        TaskInfo.Builder taskBuilder) {
      LOG.info(String.format("Setting DOCKER Task. %s", taskConfig.getExecutorConfig().getData()));
      IDockerContainer config = taskConfig.getContainer().getDocker();
      Iterable<Protos.Parameter> parameters = Iterables.transform(config.getParameters(),
          new Function<IDockerParameter, Protos.Parameter>() {
            @Override public Protos.Parameter apply(IDockerParameter item) {
              return Protos.Parameter.newBuilder().setKey(item.getName())
                .setValue(item.getValue()).build();
            }
          });

      ContainerInfo.DockerInfo.Builder dockerBuilder = ContainerInfo.DockerInfo.newBuilder()
          .setImage(config.getImage()).addAllParameters(parameters);
      ContainerInfo.Builder containerBuilder = ContainerInfo.newBuilder()
          .setType(ContainerInfo.Type.DOCKER)
          .setDocker(dockerBuilder.build());

      Protos.Environment.Builder envBuilder = Protos.Environment.newBuilder();
      envBuilder.addVariables(Protos.Environment.Variable.newBuilder().setName("AURORA_TASK_ID").setValue(task.getTaskId()).build());
      envBuilder.addVariables(Protos.Environment.Variable.newBuilder().setName("AURORA_TASK_INSTANCE").setValue(Integer.toString(task.getInstanceId())).build());
      envBuilder.addVariables(Protos.Environment.Variable.newBuilder().setName("AURORA_JOB_NAME").setValue(task.getTask().getJobName()).build());

      taskBuilder.setContainer(containerBuilder.build()); 
      
      CommandInfo.Builder cmd = CommandInfo.newBuilder();
      String command = getCmdLine(taskConfig.getExecutorConfig().getData());
      LOG.info(String.format("Using CMD: %s", command));
      cmd.addUris(CommandInfo.URI.newBuilder().setValue("file:///root/.dockercfg").build());
      cmd.setValue(command).setShell(true).setEnvironment(envBuilder.build());
      taskBuilder.setCommand(cmd.build());
    }

    public static void main(String... args) {
      String in = "{\"environment\": \"devel\", \"health_check_config\": {\"initial_interval_secs\": 15.0, \"endpoint\": \"/health\", \"expected_response_code\": 0, \"expected_response\": \"ok\", \"max_consecutive_failures\": 0, \"timeout_secs\": 1.0, \"interval_secs\": 10.0}, \"container\": {\"docker\": {\"image\": \"sleepy\", \"parameters\": []}}, \"name\": \"h\", \"service\": true, \"max_task_failures\": 1, \"cron_collision_policy\": \"KILL_EXISTING\", \"enable_hooks\": false, \"cluster\": \"devcluster\", \"task\": {\"processes\": [{\"daemon\": false, \"name\": \"hello_process\", \"ephemeral\": false, \"max_failures\": 1, \"min_duration\": 5, \"cmdline\": \"echo foo!\", \"final\": false}], \"name\": \"hello docker\", \"finalization_wait\": 30, \"max_failures\": 1, \"max_concurrency\": 0, \"resources\": {\"disk\": 8388608, \"ram\": 104857600, \"cpu\": 1.0}, \"constraints\": []}, \"production\": false, \"role\": \"d\", \"lifecycle\": {\"http\": {\"graceful_shutdown_endpoint\": \"/quitquitquit\", \"port\": \"health\", \"shutdown_endpoint\": \"/abortabortabort\"}}, \"priority\": 0}";
      System.out.println(getCmdLine(in));
    }
    
    private static String getCmdLine(String executorConfig) {
      JsonFactory factory = new JsonFactory(); 
      ObjectMapper mapper = new ObjectMapper(factory); 
      TypeReference<HashMap<String,Object>> typeRef = new TypeReference<HashMap<String,Object>>() {};

      HashMap<String,Object> o = null;
      try {
        o = mapper.readValue(executorConfig, typeRef);
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) ((List) ((HashMap) o.get("task")).get("processes")).get(0);
        String cmdLine = (String) task.get("cmdline");
        return cmdLine;
      } catch (IOException e) {
        e.printStackTrace();
        return null;
      }
    }
    
    private ExecutorInfo.Builder configureTaskForExecutor(
        IAssignedTask task,
        ITaskConfig config) {

      return executorSettings.getExecutorConfig().getExecutor().toBuilder()
          .setExecutorId(getExecutorId(task.getTaskId()))
          .setSource(getInstanceSourceName(config, task.getInstanceId()));
    }

    private void configureContainerVolumes(ContainerInfo.Builder containerBuilder) {
      containerBuilder.addAllVolumes(executorSettings.getExecutorConfig().getVolumeMounts());
    }
  }
}
