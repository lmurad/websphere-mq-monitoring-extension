package com.appdynamics.extensions.webspheremq;

import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.appdynamics.extensions.webspheremq.common.Constants;
import com.appdynamics.extensions.webspheremq.config.MqMetric;
import com.appdynamics.extensions.webspheremq.config.QueueManager;
import com.appdynamics.extensions.webspheremq.config.WMQMetricOverride;
import com.appdynamics.extensions.webspheremq.metricscollector.ChannelMetricsCollector;
import com.appdynamics.extensions.webspheremq.metricscollector.MetricsCollector;
import com.appdynamics.extensions.webspheremq.metricscollector.QueueManagerMetricsCollector;
import com.appdynamics.extensions.webspheremq.metricscollector.QueueMetricsCollector;
import com.google.common.collect.Maps;
import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.headers.CMQC;
import com.ibm.mq.headers.MQDataException;
import com.ibm.mq.pcf.PCFMessageAgent;
import com.singularity.ee.agent.systemagent.api.AManagedMonitor;
import com.singularity.ee.agent.systemagent.api.exception.TaskExecutionException;

/**
 * Encapsulates all metrics collection for all artifacts related to a queue manager.
 * 
 * @author rajeevsingh
 * @version 2.0
 *
 */
public class WebsphereMQMonitorTask implements Runnable {

	public static final Logger logger = LoggerFactory.getLogger(WebsphereMQMonitorTask.class);
	private QueueManager queueManager;
	private String metricPrefix;
	private AManagedMonitor monitor;
	private MqMetric[] mqMetrics;

	public WebsphereMQMonitorTask(QueueManager queueManager, String metricPrefix, MqMetric[] mqMetrics, AManagedMonitor monitor) {
		this.queueManager = queueManager;
		this.metricPrefix = metricPrefix;
		this.monitor = monitor;
		this.mqMetrics = mqMetrics;
	}

	public void run() {

		long startTime = System.currentTimeMillis();
		try {
			logger.debug("WebSphereMQ monitor thread for queueManager " + queueManager.getName() + " started.");
			extractAndReportMetrics();
		} catch (Exception e) {
			logger.error("Error in run of " + Thread.currentThread().getName(), e);
		} finally {
			long endTime = System.currentTimeMillis() - startTime;
			logger.debug("WebSphereMQ monitor thread for queueManager " + queueManager.getName() + " ended. Time taken = " + endTime);
		}
	}

	private void extractAndReportMetrics() throws MQException, TaskExecutionException {
		Map<String, Map<String, WMQMetricOverride>> metricsMap = getMetricsToReport();
		WMQauthenticationAndAuthorization auth = new WMQauthenticationAndAuthorization(queueManager);
		Hashtable env = auth.getMQEnvironment();
		extractAndReportInternal(env, metricsMap);
	}

	/**
	 * Returns master data structure,This map will contain only those metrics which are to be reported to controller.<br>
	 * It contains metric type as key and a map of metric and WMQMetricOverride as value,<br>
	 * entryset of internal map implicitly represents metrics to be reported.
	 */
	private Map<String, Map<String, WMQMetricOverride>> getMetricsToReport() {
		Map<String, Map<String, WMQMetricOverride>> metricsMap = Maps.newHashMap();
		for (MqMetric mqMetric : mqMetrics) {
			String metricType = mqMetric.getMetricsType();
			List includeMetrics = (List) mqMetric.getMetrics().get("include");
			Map<String, WMQMetricOverride> metricToReport = Maps.newHashMap();
			if (includeMetrics != null) {
				metricToReport = gatherMetricNamesByApplyingIncludeFilter(includeMetrics);
			}
			metricsMap.put(metricType, metricToReport);
		}
		return metricsMap;
	}

	private Map<String, WMQMetricOverride> gatherMetricNamesByApplyingIncludeFilter(List includeMetrics) {
		Map<String, WMQMetricOverride> overrideMap = Maps.newHashMap();
		for (Object inc : includeMetrics) {
			Map metric = (Map) inc;
			// Get the First Entry which is the metric
			Map.Entry firstEntry = (Map.Entry) metric.entrySet().iterator().next();
			String metricName = firstEntry.getKey().toString();
			WMQMetricOverride override = new WMQMetricOverride();
			override.setAlias(firstEntry.getValue().toString());
			override.setMultiplier(metric.get("multiplier") != null ? Double.parseDouble(metric.get("multiplier").toString()) : Constants.DEFAULT_MULTIPLIER);
			String metricType = metric.get("metricType") != null ? metric.get("metricType").toString() : Constants.DEFAULT_METRIC_TYPE;
			String[] metricTypes = metricType.split(" ");
			override.setAggregator(metricTypes[0]);
			override.setTimeRollup(metricTypes[1]);
			override.setClusterRollup(metricTypes[2]);
			override.setMetricKey(metricName);
			override.setIbmConstant((String) metric.get("ibmConstant"));
			if (override.getConstantValue() == -1) {
				// Only add the metric which is valid, if constant value
				// resolutes to -1 then it is invalid.
				logger.warn("{} is not a valid valid metric, this metric will not be processed", override.getIbmConstant());
			} else {
				overrideMap.put(metricName, override);
			}
			logger.debug("Override Definition: " + override.toString());
		}
		return overrideMap;
	}

	private void extractAndReportInternal(Hashtable env, Map<String, Map<String, WMQMetricOverride>> metricsMap) throws MQException, TaskExecutionException {

		MQQueueManager ibmQueueManager = null;
		if (env != null) {
			ibmQueueManager = new MQQueueManager(queueManager.getName(), env);
		} else {
			ibmQueueManager = new MQQueueManager(queueManager.getName());
		}

		logger.debug("Intializing PCFMessageAgent...");
		PCFMessageAgent agent = new PCFMessageAgent(ibmQueueManager);
		logger.debug("Sending PCFMessageAgent.connect() request....");
		// Note that agent.connect() method is synchronized
		agent.connect(queueManager.getName());
		logger.debug(" PCFMessageAgent connected");

		Map<String, WMQMetricOverride> qMgrMetricsToReport = metricsMap.get(Constants.METRIC_TYPE_QUEUE_MANAGER);
		if (qMgrMetricsToReport != null) {
			MetricsCollector qMgrMetricsCollector = new QueueManagerMetricsCollector(qMgrMetricsToReport, this.monitor, agent, queueManager, this.metricPrefix);
			qMgrMetricsCollector.processFilterAndPublishMetrics();
		} else {
			logger.warn("No queue manager metrics to report");
		}

		Map<String, WMQMetricOverride> channelMetricsToReport = metricsMap.get(Constants.METRIC_TYPE_CHANNEL);
		if (channelMetricsToReport != null) {
			MetricsCollector channelMetricsCollector = new ChannelMetricsCollector(channelMetricsToReport, this.monitor, agent, queueManager, this.metricPrefix);
			channelMetricsCollector.processFilterAndPublishMetrics();
		} else {
			logger.warn("No channel metrics to report");
		}

		Map<String, WMQMetricOverride> queueMetricsToReport = metricsMap.get(Constants.METRIC_TYPE_QUEUE);
		if (queueMetricsToReport != null) {
			MetricsCollector queueMetricsCollector = new QueueMetricsCollector(queueMetricsToReport, this.monitor, agent, queueManager, this.metricPrefix);
			queueMetricsCollector.processFilterAndPublishMetrics();
		} else {
			logger.warn("No queue metrics to report");
		}
		DestroyAgent(agent);
	}

	/*
	 * Do not Remove this method even if it is not used
	 */
	private PCFMessageAgent createAgent() throws MQException {

		PCFMessageAgent agent = null;
		boolean client = Constants.TRANSPORT_TYPE_CLIENT.equals(queueManager.getTransportType());
		try {
			if (!client) {
				// Connect to the local queue manager.
				agent = new PCFMessageAgent(queueManager.getName());
			} else {
				// Connect to the client and define the queue manager host, port and channel.
				// Notice that the method does not take a queue manager name. It is assuming that the
				// default QM will be used.
				agent = new PCFMessageAgent(queueManager.getHost(), queueManager.getPort(), queueManager.getChannelName());
			}
		} catch (MQException mqde) {
			if (mqde.reasonCode == CMQC.MQRC_Q_MGR_NAME_ERROR) {
				StringBuilder errMsgBuilder = new StringBuilder("Either could not find the ");
				if (client) {
					errMsgBuilder.append("default queue manager at \"" + queueManager.getHost() + "\", port \"" + queueManager.getPort() + "\"");
				} else {
					errMsgBuilder.append("queue manager \"" + queueManager.getName() + "\"");
				}
				errMsgBuilder.append(" or could not find the default channel \"" + queueManager.getChannelName() + "\" on the queue manager.");
				logger.error(errMsgBuilder.toString());
			}

			throw mqde;
		}
		return agent;
	}

	/**
	 * Destroy the agent.
	 * 
	 * @throws MQDataException
	 */
	private void DestroyAgent(PCFMessageAgent agent) throws MQException {
		// Disconnect the agent.
		agent.disconnect();
	}

}
