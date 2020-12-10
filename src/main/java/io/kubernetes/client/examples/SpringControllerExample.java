/*
Copyright 2020 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client.examples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.kubernetes.client.examples.models.V1ConfigClient;
import io.kubernetes.client.examples.models.V1ConfigClientList;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.DefaultControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.prometheus.client.CollectorRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class SpringControllerExample {

	public static void main(String[] args) {
		SpringApplication.run(SpringControllerExample.class, args);
	}

	@Configuration
	public static class AppConfig {

		@Bean
		public CommandLineRunner commandLineRunner(SharedInformerFactory sharedInformerFactory, Controller controller) {
			return args -> {
				System.out.println("starting informers..");
				sharedInformerFactory.startAllRegisteredInformers();

				System.out.println("running controller..");
				controller.run();
			};
		}

		@Bean
		public Controller nodePrintingController(SharedInformerFactory sharedInformerFactory,
				ConfigClientReconciler reconciler) {
			DefaultControllerBuilder builder = ControllerBuilder.defaultBuilder(sharedInformerFactory);
			builder = builder.watch((q) -> {
				return ControllerBuilder.controllerWatchBuilder(V1ConfigClient.class, q).withWorkQueueKeyFunc(node -> {
					System.err.println("ConfigClient: " + node.getMetadata().getName());
					return new Request(node.getMetadata().getNamespace(), node.getMetadata().getName());
				}).withResyncPeriod(Duration.ofHours(1)).build();
			});
			builder.withWorkerCount(2);
			return builder.withReconciler(reconciler).withName("configClientController").build();
		}

		@Bean
		public SharedIndexInformer<V1ConfigClient> nodeInformer(ApiClient apiClient,
				SharedInformerFactory sharedInformerFactory) {
			final GenericKubernetesApi<V1ConfigClient, V1ConfigClientList> api = new GenericKubernetesApi<>(
					V1ConfigClient.class, V1ConfigClientList.class, "spring.io", "v1", "configclients", apiClient);
			return sharedInformerFactory.sharedIndexInformerFor(api, V1ConfigClient.class, 0);
		}

		@Bean
		// https://github.com/spring-projects/spring-boot/issues/24377
		public CollectorRegistry collectorRegistry() {
			return CollectorRegistry.defaultRegistry;
		}

	}

	@Component
	public static class ConfigClientReconciler implements Reconciler {

		@Value("${namespace}")
		private String namespace;

		private GenericKubernetesApi<V1ConfigMap, V1ConfigMapList> configmaps;

		private SharedIndexInformer<V1ConfigClient> nodeInformer;

		public ConfigClientReconciler(SharedIndexInformer<V1ConfigClient> nodeInformer, ApiClient apiClient) {
			super();
			this.nodeInformer = nodeInformer;
			this.configmaps = new GenericKubernetesApi<>(V1ConfigMap.class, V1ConfigMapList.class, "", "v1",
					"configmaps", apiClient);
		}

		@Override
		public Result reconcile(Request request) {
			Lister<V1ConfigClient> nodeLister = new Lister<>(nodeInformer.getIndexer(), request.getNamespace());

			V1ConfigClient node = nodeLister.get(request.getName());

			if (node != null) {

				System.out.println("reconciling " + node.getMetadata().getName());
				List<V1ConfigMap> items = new ArrayList<>();
				for (V1ConfigMap item : configmaps.list(request.getNamespace()).getObject().getItems()) {
					System.out.println("  configmap " + item.getMetadata().getName());
					for (V1OwnerReference owner : item.getMetadata().getOwnerReferences()) {
						if (node.getMetadata().getUid().equals(owner.getUid())) {
							System.out.println("    owned " + owner.getName());
							items.add(item);
							break;
						}
					}
				}

				V1ConfigMap actual = null;
				if (items.size() == 1) {
					actual = items.get(0);
				}
				else {
					for (V1ConfigMap item : items) {
						configmaps.delete(item.getMetadata().getNamespace(), item.getMetadata().getName());
					}
				}

				V1ConfigMap desired = desired(node);
				if (desired == null) {
					configmaps.delete(actual.getMetadata().getNamespace(), actual.getMetadata().getName());
					return new Result(false);
				}

				V1OwnerReference v1OwnerReference = new V1OwnerReference();
				v1OwnerReference.setKind(node.getKind());
				v1OwnerReference.setName(node.getMetadata().getName());
				v1OwnerReference.setBlockOwnerDeletion(true);
				v1OwnerReference.setController(true);
				v1OwnerReference.setUid(node.getMetadata().getUid());
				v1OwnerReference.setApiVersion(node.getApiVersion());
				desired.getMetadata().addOwnerReferencesItem(v1OwnerReference);
				if (actual == null) {
					try {
						actual = configmaps.create(desired).throwsApiException().getObject();
					}
					catch (ApiException e) {
						throw new IllegalStateException(e);
					}
				}

				harmonizeImmutableFields(actual, desired);
				if (semanticEquals(desired, actual)) {
					return new Result(false);
				}

				V1ConfigMap current = actual;
				mergeBeforeUpdate(current, desired);

				configmaps.update(current);

			}

			return new Result(false);

		}

		private void mergeBeforeUpdate(V1ConfigMap current, V1ConfigMap desired) {
			current.getMetadata().setLabels(desired.getMetadata().getLabels());
			current.setData(desired.getData());
		}

		private boolean semanticEquals(V1ConfigMap desired, V1ConfigMap actual) {
			if (actual == null && desired != null || desired == null && actual != null) {
				return false;
			}
			return actual != null && mapEquals(desired.getMetadata().getLabels(), actual.getMetadata().getLabels())
					&& mapEquals(desired.getData(), actual.getData());
		}

		private boolean mapEquals(Map<String, String> actual, Map<String, String> desired) {
			if (actual == null && desired != null) {
				return desired.isEmpty();
			}
			if (desired == null && actual != null) {
				return actual.isEmpty();
			}
			return Objects.equals(actual, desired);
		}

		private void harmonizeImmutableFields(V1ConfigMap actual, V1ConfigMap desired) {
		}

		private V1ConfigMap desired(V1ConfigClient node) {
			V1ConfigMap config = new V1ConfigMap();
			V1ObjectMeta metadata = new V1ObjectMeta();
			metadata.setName(node.getMetadata().getName());
			metadata.setNamespace(node.getMetadata().getNamespace());
			config.setMetadata(metadata);
			config.setData(Collections.emptyMap());
			return config;
		}

	}

}
