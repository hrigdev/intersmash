/**
 * Copyright (C) 2023 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.intersmash.provision.operator;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jboss.intersmash.IntersmashConfig;
import org.jboss.intersmash.application.operator.KafkaOperatorApplication;
import org.jboss.intersmash.provision.Provisioner;
import org.slf4j.event.Level;

import cz.xtf.core.waiting.SimpleWaiter;
import cz.xtf.core.waiting.failfast.FailFastCheck;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.StatusDetails;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionList;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaList;
import io.strimzi.api.kafka.KafkaTopicList;
import io.strimzi.api.kafka.KafkaUserList;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaUser;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Deploys an application that implements {@link KafkaOperatorApplication} interface and which is
 * extended by this class.
 */
@Slf4j
public abstract class KafkaOperatorProvisioner<C extends NamespacedKubernetesClient>
		extends OperatorProvisioner<KafkaOperatorApplication, C>
		implements Provisioner<KafkaOperatorApplication> {

	public KafkaOperatorProvisioner(@NonNull KafkaOperatorApplication application) {
		super(application, KafkaOperatorProvisioner.OPERATOR_ID);
	}

	// =================================================================================================================
	// Kafka related
	// =================================================================================================================
	public List<Pod> getClusterOperatorPods() {
		return this.client()
				.pods()
				.inNamespace(this.client().getNamespace())
				.withLabel("strimzi.io/kind", "cluster-operator")
				.list()
				.getItems();
	}

	/**
	 * Get list of all Kafka pods on OpenShift instance with regards this Kafka cluster. <br>
	 * <br>
	 * Note: Operator actually creates also pods for Kafka, instance entity operator pods and cluster
	 * operator pod. But we list only Kafka related pods here.
	 *
	 * @return list of Kafka pods
	 */
	public List<Pod> getKafkaPods() {
		List<Pod> kafkaPods = this.client()
				.pods()
				.inNamespace(this.client().getNamespace())
				.withLabel("app.kubernetes.io/name", "kafka")
				.list()
				.getItems();
		// Let's filter out just those who match particular naming
		for (Pod kafkaPod : kafkaPods) {
			if (!kafkaPod.getMetadata().getName().contains(getApplication().getName() + "-kafka-")) {
				kafkaPods.remove(kafkaPod);
			}
		}

		return kafkaPods;
	}

	/**
	 * Get list of all Zookeeper pods on OpenShift instance with regards this Kafka cluster. <br>
	 * <br>
	 * Note: Operator actually creates also pods for Kafka, instance entity operator pods and cluster
	 * operator pod. But we list only Zookeeper related pods here.
	 *
	 * @return list of Kafka pods
	 */
	public List<Pod> getZookeeperPods() {
		List<Pod> kafkaPods = this.client()
				.pods()
				.inNamespace(this.client().getNamespace())
				.withLabel("app.kubernetes.io/name", "zookeeper")
				.list()
				.getItems();
		// Let's filter out just those who match particular naming
		for (Pod kafkaPod : kafkaPods) {
			if (!kafkaPod.getMetadata().getName().contains(getApplication().getName() + "-zookeeper-")) {
				kafkaPods.remove(kafkaPod);
			}
		}
		return kafkaPods;
	}

	public void waitForKafkaClusterCreation() {
		FailFastCheck ffCheck = getFailFastCheck();
		int expectedReplicas = getApplication().getKafka().getSpec().getKafka().getReplicas();
		new SimpleWaiter(() -> kafka().get() != null)
				.failFast(ffCheck)
				.reason("Wait for Kafka cluster instance to be initialized.")
				.level(Level.DEBUG)
				.waitFor();
		new SimpleWaiter(() -> kafka().get().getStatus() != null)
				.failFast(ffCheck)
				.reason("Wait for a status field of the Kafka cluster instance to be initialized.")
				.level(Level.DEBUG)
				.waitFor();
		new SimpleWaiter(() -> kafka().get().getStatus().getConditions() != null)
				.failFast(ffCheck)
				.reason("Wait for a conditions field of the Kafka cluster instance to be initialized.")
				.level(Level.DEBUG)
				.waitFor();
		new SimpleWaiter(() -> !kafka().get().getStatus().getConditions().isEmpty())
				.failFast(ffCheck)
				.reason(
						"Wait for a conditions field of the Kafka cluster instance to contain at least one condition.")
				.level(Level.DEBUG)
				.waitFor();
		new SimpleWaiter(
				() -> kafka().get().getStatus().getConditions().stream()
						.anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus())))
				.failFast(ffCheck)
				.reason("Wait for a conditions field of the Kafka cluster instance to be in state 'Ready'.")
				.level(Level.DEBUG)
				.onSuccess(
						() -> {
							listKafkaClusterCreationConditions(
									true, "Waiting for the Kafka cluster instance was successful.");
						})
				.onFailure(
						() -> {
							listKafkaClusterCreationConditions(
									false, "Waiting for the Kafka cluster instance ended with an error.");
						})
				.onTimeout(
						() -> {
							listKafkaClusterCreationConditions(
									false, "Waiting for the Kafka cluster instance ended with a timeout.");
						})
				.waitFor();
		new SimpleWaiter(() -> getKafkaPods().size() == expectedReplicas)
				.failFast(ffCheck)
				.reason("Wait for expected number of replicas of Kafka to be active.")
				.level(Level.DEBUG)
				.waitFor();
	}

	private void listKafkaClusterCreationConditions(boolean success, String message) {
		String completeMessage = message + " Here is the list of instance conditions found there:";
		if (success) {
			log.info(completeMessage);
		} else {
			log.error(completeMessage);
		}

		kafka().get().getStatus().getConditions().stream()
				.forEach(
						c -> {
							String conditionMessage = "    |- " + c.getType() + ":" + c.getStatus() + ":" + c.getMessage();
							if (success) {
								log.info(conditionMessage);
							} else {
								log.error(conditionMessage);
							}
						});
	}

	private void waitForKafkaTopicCreation(KafkaTopic topic) {
		String topicName = topic.getMetadata().getName();

		new SimpleWaiter(
				() -> kafkasTopicClient().list().getItems().stream()
						.filter(t -> topicName.equals(t.getMetadata().getName()))
						.count() == 1,
				"Waiting for topic '" + topicName + "' to be created")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasTopicClient().list().getItems().stream()
						.filter(t -> topicName.equals(t.getMetadata().getName()))
						.allMatch(t -> t.getStatus() != null),
				"Waiting for topic '" + topicName + "' status is non-null")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasTopicClient().list().getItems().stream()
						.filter(t -> topicName.equals(t.getMetadata().getName()))
						.allMatch(t -> t.getStatus().getConditions() != null),
				"Waiting for topic '" + topicName + "' conditions are non-null")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasTopicClient().list().getItems().stream()
						.filter(t -> topicName.equals(t.getMetadata().getName()))
						.allMatch(t -> t.getStatus().getConditions().size() > 0),
				"Waiting for topic '" + topicName + "' conditions size is greater than 0")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasTopicClient().list().getItems().stream()
						.filter(t -> topicName.equals(t.getMetadata().getName()))
						.allMatch(t -> "Ready".equals(t.getStatus().getConditions().get(0).getType())),
				"Waiting for topic '" + topicName + "' condition to be 'Ready'")
				.level(Level.DEBUG)
				.waitFor();
	}

	private void waitForKafkaUserCreation(KafkaUser user) {
		String userName = user.getMetadata().getName();

		new SimpleWaiter(
				() -> kafkasUserClient().list().getItems().stream()
						.filter(u -> userName.equals(u.getMetadata().getName()))
						.count() == 1,
				"Waiting for user '" + userName + "' to be created")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasUserClient().list().getItems().stream()
						.filter(u -> userName.equals(u.getMetadata().getName()))
						.allMatch(u -> u.getStatus() != null),
				"Waiting for user '" + userName + "' status is non-null")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasUserClient().list().getItems().stream()
						.filter(u -> userName.equals(u.getMetadata().getName()))
						.allMatch(u -> u.getStatus().getConditions() != null),
				"Waiting for user '" + userName + "' conditions are non-null")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasUserClient().list().getItems().stream()
						.filter(u -> userName.equals(u.getMetadata().getName()))
						.allMatch(u -> u.getStatus().getConditions().size() > 0),
				"Waiting for user '" + userName + "' conditions size is greater than 0")
				.level(Level.DEBUG)
				.waitFor();

		new SimpleWaiter(
				() -> kafkasUserClient().list().getItems().stream()
						.filter(u -> userName.equals(u.getMetadata().getName()))
						.allMatch(u -> "Ready".equals(u.getStatus().getConditions().get(0).getType())),
				"Waiting for user '" + userName + "' condition to be 'Ready'")
				.level(Level.DEBUG)
				.waitFor();
	}

	// =================================================================================================================
	// Related to generic provisioning behavior
	// =================================================================================================================
	@Override
	protected String getOperatorCatalogSource() {
		return IntersmashConfig.kafkaOperatorCatalogSource();
	}

	@Override
	protected String getOperatorIndexImage() {
		return IntersmashConfig.kafkaOperatorIndexImage();
	}

	@Override
	protected String getOperatorChannel() {
		return IntersmashConfig.kafkaOperatorChannel();
	}

	@Override
	public List<Pod> getPods() {
		return this.client()
				.pods()
				.inNamespace(this.client().getNamespace())
				.withLabel("strimzi.io/cluster", getApplication().getName())
				.list()
				.getItems();
	}

	@Override
	public void deploy() {
		subscribe();
		if (getApplication().getKafka() != null) {
			// Create a Kafka cluster instance
			kafkasClient().createOrReplace(getApplication().getKafka());
			waitForKafkaClusterCreation();
		}
		if (getApplication().getTopics() != null) {
			for (KafkaTopic topic : getApplication().getTopics()) {
				// Create a Kafka topic instance
				kafkasTopicClient().createOrReplace(topic);

				// Wait for it to be created and ready...
				waitForKafkaTopicCreation(topic);
			}
		}
		if (getApplication().getUsers() != null) {
			for (KafkaUser user : getApplication().getUsers()) {
				// Create a Kafka user instance
				kafkasUserClient().createOrReplace(user);

				// Wait for it to be created and ready...
				waitForKafkaUserCreation(user);
			}
		}
	}

	@Override
	public void undeploy() {
		// delete the resources
		List<StatusDetails> deletionDetails;
		boolean deleted;
		if (getApplication().getUsers() != null) {
			deletionDetails = kafkasUserClient().delete();
			deleted = deletionDetails.stream().allMatch(d -> d.getCauses().isEmpty());
			if (!deleted) {
				log.warn(
						"Wasn't able to remove all relevant 'Kafka User' resources created for '{}' instance!",
						getApplication().getName());
			}
			new SimpleWaiter(() -> kafkasUserClient().list().getItems().isEmpty())
					.level(Level.DEBUG)
					.waitFor();
		}
		if (getApplication().getTopics() != null) {
			deletionDetails = kafkasTopicClient().delete();
			deleted = deletionDetails.stream().allMatch(d -> d.getCauses().isEmpty());
			if (!deleted) {
				log.warn(
						"Wasn't able to remove all relevant 'Kafka Topic' resources created for '{}' instance!",
						getApplication().getName());
			}
			new SimpleWaiter(() -> kafkasTopicClient().list().getItems().isEmpty())
					.level(Level.DEBUG)
					.waitFor();
		}
		if (getApplication().getKafka() != null) {
			deletionDetails = kafka().withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
			deleted = deletionDetails.stream().allMatch(d -> d.getCauses().isEmpty());
			if (!deleted) {
				log.warn(
						"Wasn't able to remove all relevant 'Kafka' resources created for '{}' instance!",
						getApplication().getName());
			}
			new SimpleWaiter(() -> getKafkaPods().isEmpty()).level(Level.DEBUG).waitFor();
		}
		unsubscribe();
		BooleanSupplier bs = () -> getPods().stream()
				.noneMatch(
						p -> p.getMetadata().getLabels().get("name") != null
								&& p.getMetadata()
										.getLabels()
										.get("name")
										.equals(getApplication().getName() + "-cluster-operator"));
		new SimpleWaiter(
				bs,
				TimeUnit.MINUTES,
				2,
				"Waiting for 0 pods with label \"name\"="
						+ getApplication().getName()
						+ "-cluster-operator")
				.waitFor();
	}

	@Override
	public void scale(int replicas, boolean wait) {
		Kafka kafka = getApplication().getKafka();
		// Note we change replicas of Kafka instances only (no Zookeeper replicas number change).
		kafka.getSpec().getKafka().setReplicas(replicas);

		kafkasClient().createOrReplace(kafka);

		if (wait) {
			waitForKafkaClusterCreation();
		}
	}

	// =================================================================================================================
	// Client related
	// =================================================================================================================
	// this is the packagemanifest for the hyperfoil operator;
	// you can get it with command:
	// oc get packagemanifest hyperfoil-bundle -o template --template='{{ .metadata.name }}'
	public static String OPERATOR_ID = IntersmashConfig.kafkaOperatorPackageManifest();

	/**
	 * Generic CRD client which is used by client builders default implementation to build the CRDs
	 * client
	 *
	 * @return A {@link NonNamespaceOperation} instance that represents a
	 */
	protected abstract NonNamespaceOperation<CustomResourceDefinition, CustomResourceDefinitionList, Resource<CustomResourceDefinition>> customResourceDefinitionsClient();

	/**
	 * Get a client capable of working with {@link Kafka} custom resource on our OpenShift instance.
	 *
	 * @return client for operations with {@link Kafka} custom resource on our OpenShift instance
	 */
	public NonNamespaceOperation<Kafka, KafkaList, Resource<Kafka>> kafkasClient() {
		return Crds.kafkaOperation(this.client()).inNamespace(this.client().getNamespace());
	}

	/**
	 * Get a client capable of working with {@link KafkaUser} custom resource on our OpenShift
	 * instance.
	 *
	 * @return client for operations with {@link KafkaUser} custom resource on our OpenShift instance
	 */
	public NonNamespaceOperation<KafkaUser, KafkaUserList, Resource<KafkaUser>> kafkasUserClient() {
		return Crds.kafkaUserOperation(this.client()).inNamespace(this.client().getNamespace());
	}

	/**
	 * Get a client capable of working with {@link KafkaTopic} custom resource on our OpenShift
	 * instance.
	 *
	 * @return client for operations with {@link KafkaTopic} custom resource on our OpenShift instance
	 */
	public NonNamespaceOperation<KafkaTopic, KafkaTopicList, Resource<KafkaTopic>> kafkasTopicClient() {
		return Crds.topicOperation(this.client()).inNamespace(this.client().getNamespace());
	}

	/**
	 * Kafka cluster resource on OpenShift instance. The Kafka resource returned is the one that is
	 * tied with the appropriate Application for which this provisioner is created for. The instance
	 * is determined based on the name value defined in specifications.
	 *
	 * @return returns Kafka cluster resource on OpenShift instance that is tied with our relevant
	 *     Application only
	 */
	public Resource<Kafka> kafka() {
		return kafkasClient().withName(getApplication().getKafka().getMetadata().getName());
	}

	public KafkaUserList getUsers() {
		return kafkasUserClient().list();
	}

	public KafkaTopicList getTopics() {
		return kafkasTopicClient().list();
	}
}
