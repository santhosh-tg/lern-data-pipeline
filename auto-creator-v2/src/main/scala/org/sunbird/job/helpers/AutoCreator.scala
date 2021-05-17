package org.sunbird.job.helpers

import java.io.File

import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import org.sunbird.job.domain.`object`.{DefinitionCache, ObjectDefinition}
import org.sunbird.job.model.{ExtDataConfig, ObjectData}
import org.sunbird.job.task.AutoCreatorV2Config
import org.sunbird.job.util.{CassandraUtil, CloudStorageUtil, FileUtils, HTTPResponse, HttpUtil, JSONUtil, Neo4JUtil}

trait AutoCreator extends ObjectUpdater with CollectionUpdater with HierarchyEnricher {

	private[this] val logger = LoggerFactory.getLogger(classOf[AutoCreator])

	def getObject(identifier: String, objType: String, downloadUrl: String, metaUrl: Option[String] = None)(implicit config: AutoCreatorV2Config, objDef: ObjectDefinition): ObjectData = {
		val extractPath = extractDataZip(identifier, downloadUrl)
		val manifestData = getObjectDetails(identifier, extractPath, metaUrl)
		val metadata = manifestData.filterKeys(k => !(objDef.getRelationLabels.contains(k) || objDef.externalProperties.contains(k)))
		val extData = manifestData.filterKeys(k => objDef.externalProperties.contains(k))
		val hierarchy = getHierarchy(extractPath, objType)(config)
		val externalData = if (hierarchy.nonEmpty) extData ++ Map("hierarchy" -> hierarchy) else extData
		new ObjectData(identifier, objType, metadata, Some(externalData), Some(hierarchy))
	}

  private def extractDataZip(identifier: String, downloadUrl: String): String = {
    val suffix = FilenameUtils.getName(downloadUrl).replace(".ecar", ".zip")
    val zipFile: File = FileUtils.copyURLToFile(identifier, downloadUrl, suffix).get
    logger.info("zip file path :: " + zipFile.getAbsolutePath)
    val extractPath = FileUtils.getBasePath(identifier)
    logger.info("zip extracted path :: " + extractPath)
    FileUtils.extractPackage(zipFile, extractPath)
    extractPath
  }

  private def getObjectDetails(identifier: String, extractPath: String, metaUrl: Option[String]): Map[String, AnyRef] = {
    val manifest = FileUtils.readJsonFile(extractPath, "manifest.json")
    manifest.getOrElse("archive", Map()).asInstanceOf[Map[String, AnyRef]]
      .getOrElse("items", List()).asInstanceOf[List[Map[String, AnyRef]]]
      .filter(p => StringUtils.equalsIgnoreCase(identifier, p.getOrElse("identifier", "").asInstanceOf[String]))
      .headOption.getOrElse(Map())
  }

  private def getHierarchy(extractPath: String, objectType: String)(implicit config: AutoCreatorV2Config): Map[String, AnyRef] = {
    if (config.expandableObjects.contains(objectType))
      FileUtils.readJsonFile(extractPath, "hierarchy.json")
        .getOrElse(objectType.toLowerCase(), Map()).asInstanceOf[Map[String, AnyRef]]
    else Map[String, AnyRef]()
  }

	def enrichMetadata(obj: ObjectData, eventMeta: Map[String, AnyRef])(implicit config: AutoCreatorV2Config): ObjectData = {
		val sysMeta = Map("IL_UNIQUE_ID" -> obj.identifier, "IL_FUNC_OBJECT_TYPE" -> obj.objectType, "IL_SYS_NODE_TYPE" -> "DATA_NODE")
		val oProps: Map[String, AnyRef] = config.overrideManifestProps.map(prop => (prop, eventMeta.getOrElse(prop, ""))).toMap
		new ObjectData(obj.identifier, obj.objectType, (obj.metadata ++ sysMeta ++ oProps), obj.extData, obj.hierarchy)
	}

	def processCloudMeta(obj: ObjectData)(implicit config: AutoCreatorV2Config, cloudStorageUtil: CloudStorageUtil): ObjectData = {
		val data = config.cloudProps.filter(x => obj.metadata.get(x).nonEmpty).flatMap(prop => {
			if (StringUtils.equalsIgnoreCase("variants", prop)) obj.metadata.getOrElse("variants", Map()).asInstanceOf[Map[String, AnyRef]].toList
			else List((prop, obj.metadata.getOrElse(prop, "")))
		}).toMap
		val updatedUrls: Map[String, AnyRef] = data.map(en => {
			logger.info("processCloudMeta :: key : " + en._1 + " | url : " + en._2)
			val file = FileUtils.copyURLToFile(obj.identifier, en._2.asInstanceOf[String], FilenameUtils.getName(en._2.asInstanceOf[String]))
			val url = FileUtils.uploadFile(file, obj.identifier, obj.metadata.getOrElse("IL_FUNC_OBJECT_TYPE", "").asInstanceOf[String])
			(en._1, url.getOrElse(""))
		})
		logger.info("processCloudMeta :: updatedUrls : " + updatedUrls)
		val updatedMeta = obj.metadata ++ updatedUrls.filterKeys(k => !List("spine", "online").contains(k)) ++ Map("variants" -> getVariantMap(updatedUrls))
		new ObjectData(obj.identifier, obj.objectType, updatedMeta, obj.extData, obj.hierarchy)
	}

	def getVariantMap(map: Map[String, AnyRef]): Map[String, AnyRef] = {
		List("full", "online", "spine").filter(x => map.contains(x)).map(m => (m, map.getOrElse(m, "").asInstanceOf[String])).toMap
	}

	def processChildren(children: Map[String, AnyRef])(implicit config: AutoCreatorV2Config, neo4JUtil: Neo4JUtil, cassandraUtil: CassandraUtil, cloudStorageUtil: CloudStorageUtil, defCache: DefinitionCache): Map[String, ObjectData] = {
		children.flatMap(ch => {
			logger.info("Processing Children Having Identifier : " + ch._1)
			val objType = ch._2.asInstanceOf[Map[String, AnyRef]].getOrElse("objectType", "").asInstanceOf[String]
			val definition: ObjectDefinition = defCache.getDefinition(objType, config.schemaSupportVersionMap.getOrElse(objType.toLowerCase(), "1.0").asInstanceOf[String], config.definitionBasePath)
			val downloadUrl = ch._2.asInstanceOf[Map[String, AnyRef]].getOrElse("downloadUrl", "").asInstanceOf[String]
			val obj: ObjectData = getObject(ch._1, objType, downloadUrl)(config, definition)
			logger.info("graph metadata for " + obj.identifier + " : " + obj.metadata)
			val enObj = enrichMetadata(obj, ch._2.asInstanceOf[Map[String, AnyRef]])(config)
			logger.info("enriched metadata for " + enObj.identifier + " : " + enObj.metadata)
			val updatedObj = processCloudMeta(enObj)(config, cloudStorageUtil)
			logger.info("final updated metadata for " + updatedObj.identifier + " : " + JSONUtil.serialize(updatedObj.metadata))
			val extConfig = ExtDataConfig(config.getString(updatedObj.objectType.toLowerCase + "_keyspace", ""), definition.getExternalTable, definition.getExternalPrimaryKey, definition.getExternalProps)
			saveExternalData(updatedObj.identifier, updatedObj.extData.getOrElse(Map()), extConfig)(cassandraUtil)
			saveGraphData(updatedObj.identifier, updatedObj.metadata, definition)(neo4JUtil)
			Map(updatedObj.identifier-> updatedObj)
		})
	}

}
