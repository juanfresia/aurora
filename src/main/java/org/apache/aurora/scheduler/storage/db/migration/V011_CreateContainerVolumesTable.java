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
package org.apache.aurora.scheduler.storage.db.migration;

import java.math.BigDecimal;

import org.apache.ibatis.migration.MigrationScript;

public class V011_CreateContainerVolumesTable implements MigrationScript {
  @Override
  public BigDecimal getId() {
    return BigDecimal.valueOf(11L);
  }

  @Override
  public String getDescription() {
    return "Create the task_config_volumes and volume_modes tables";
  }

  @Override
  public String getUpScript() {
    return "CREATE TABLE IF NOT EXISTS volume_modes("
        + "id INT PRIMARY KEY,"
        + "name VARCHAR NOT NULL,"
        + "UNIQUE(name)"
        + ");"
        + "CREATE TABLE IF NOT EXISTS task_config_volumes("
        + "id IDENTITY,"
        + "task_config_id BIGINT NOT NULL REFERENCES task_configs(id) ON DELETE CASCADE,"
        + "host_path VARCHAR NOT NULL,"
        + "container_path VARCHAR NOT NULL,"
        + "mode INT NOT NULL REFERENCES volume_modes(id),"
        + "UNIQUE(task_config_id)"
        + ");";
  }

  @Override
  public String getDownScript() {
    return "DROP TABLE IF EXISTS volume_modes;"
        + "DROP TABLE IF EXISTS task_config_volumes;";
  }
}