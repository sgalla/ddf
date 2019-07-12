/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.catalog.content.impl;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.io.ByteSource;
import ddf.catalog.content.StorageException;
import ddf.catalog.content.StorageProvider;
import ddf.catalog.content.data.ContentItem;
import ddf.catalog.content.data.impl.ContentItemImpl;
import ddf.catalog.content.data.impl.ContentItemValidator;
import ddf.catalog.content.operation.CreateStorageRequest;
import ddf.catalog.content.operation.CreateStorageResponse;
import ddf.catalog.content.operation.DeleteStorageRequest;
import ddf.catalog.content.operation.DeleteStorageResponse;
import ddf.catalog.content.operation.ReadStorageRequest;
import ddf.catalog.content.operation.ReadStorageResponse;
import ddf.catalog.content.operation.StorageRequest;
import ddf.catalog.content.operation.UpdateStorageRequest;
import ddf.catalog.content.operation.UpdateStorageResponse;
import ddf.catalog.content.operation.impl.CreateStorageResponseImpl;
import ddf.catalog.content.operation.impl.DeleteStorageResponseImpl;
import ddf.catalog.content.operation.impl.ReadStorageResponseImpl;
import ddf.catalog.content.operation.impl.UpdateStorageResponseImpl;
import ddf.catalog.data.Metacard;
import ddf.catalog.data.impl.AttributeImpl;
import ddf.mime.MimeTypeMapper;
import ddf.mime.MimeTypeResolutionException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** File system storage provider. */
public class S3StorageProvider implements StorageProvider {

  private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

  private static final Logger LOGGER = LoggerFactory.getLogger(S3StorageProvider.class);

  /** Mapper for file extensions-to-mime types (and vice versa) */
  private MimeTypeMapper mimeTypeMapper;

  private String s3Endpoint;

  private String s3Region;

  private String s3Bucket;

  private String contentPrefix;

  private String s3AccessKey;

  private String s3SecretKey;

  private Map<String, List<Metacard>> deletionMap = new ConcurrentHashMap<>();

  private Map<String, Set<ContentItem>> updateMap = new ConcurrentHashMap<>();

  private AmazonS3 amazonS3;

  public void setS3Endpoint(String s3Endpoint) {
    this.s3Endpoint = s3Endpoint;
  }

  public void setS3Region(String s3Region) {
    this.s3Region = s3Region;
  }

  public void setS3AccessKey(String s3AccessKey) {
    this.s3AccessKey = s3AccessKey;
  }

  public void setS3SecretKey(String s3SecretKey) {
    this.s3SecretKey = s3SecretKey;
  }

  public void setContentPrefix(String contentPrefix) {
    this.contentPrefix = contentPrefix;
  }

  public void setS3Bucket(String s3Bucket) {
    this.s3Bucket = s3Bucket;
  }

  public void setMimeTypeMapper(MimeTypeMapper mimeTypeMapper) {
    this.mimeTypeMapper = mimeTypeMapper;
  }

  /** Default constructor, invoked by blueprint. */
  public S3StorageProvider() {
    LOGGER.debug("File System Provider initializing...");

    createAmazonS3();
  }

  @Override
  public CreateStorageResponse create(CreateStorageRequest createRequest) throws StorageException {
    LOGGER.trace("ENTERING: create");

    List<ContentItem> contentItems = createRequest.getContentItems();

    List<ContentItem> createdContentItems = new ArrayList<>(createRequest.getContentItems().size());

    for (ContentItem contentItem : contentItems) {
      try {
        if (!ContentItemValidator.validate(contentItem)) {
          LOGGER.warn("Item is not valid: {}", contentItem);
          continue;
        }

        createdContentItems.add(generateContentItem(contentItem));
      } catch (IOException e) {
        throw new StorageException(e);
      }
    }

    CreateStorageResponse response =
        new CreateStorageResponseImpl(createRequest, createdContentItems);
    updateMap.put(createRequest.getId(), createdContentItems.stream().collect(Collectors.toSet()));

    LOGGER.trace("EXITING: create");

    return response;
  }

  @Override
  public ReadStorageResponse read(ReadStorageRequest readRequest) throws StorageException {
    LOGGER.trace("ENTERING: read");

    if (readRequest.getResourceUri() == null) {
      return new ReadStorageResponseImpl(readRequest);
    }

    URI uri = readRequest.getResourceUri();
    ContentItem returnItem = readContent(uri);
    return new ReadStorageResponseImpl(readRequest, returnItem);
  }

  @Override
  public UpdateStorageResponse update(UpdateStorageRequest updateRequest) throws StorageException {
    LOGGER.trace("ENTERING: update");

    List<ContentItem> contentItems = updateRequest.getContentItems();

    List<ContentItem> updatedItems = new ArrayList<>(updateRequest.getContentItems().size());

    for (ContentItem contentItem : contentItems) {
      try {
        if (!ContentItemValidator.validate(contentItem)) {
          LOGGER.warn("Item is not valid: {}", contentItem);
          continue;
        }

        ContentItem updateItem = contentItem;

        updatedItems.add(generateContentItem(updateItem));
      } catch (IOException | IllegalArgumentException e) {
        throw new StorageException(e);
      }
    }

    for (ContentItem contentItem : updatedItems) {
      if (contentItem.getMetacard().getResourceURI() == null
          && StringUtils.isBlank(contentItem.getQualifier())) {
        contentItem
            .getMetacard()
            .setAttribute(new AttributeImpl(Metacard.RESOURCE_URI, contentItem.getUri()));
        try {
          contentItem
              .getMetacard()
              .setAttribute(new AttributeImpl(Metacard.RESOURCE_SIZE, contentItem.getSize()));
        } catch (IOException e) {
          LOGGER.info(
              "Could not set size of content item [{}] on metacard [{}]",
              contentItem.getId(),
              contentItem.getMetacard().getId(),
              e);
        }
      }
    }

    UpdateStorageResponse response = new UpdateStorageResponseImpl(updateRequest, updatedItems);
    updateMap.put(updateRequest.getId(), updatedItems.stream().collect(Collectors.toSet()));

    LOGGER.trace("EXITING: update");

    return response;
  }

  @Override
  public DeleteStorageResponse delete(DeleteStorageRequest deleteRequest) throws StorageException {
    LOGGER.trace("ENTERING: delete");

    List<Metacard> itemsToBeDeleted = new ArrayList<>();

    List<ContentItem> deletedContentItems = new ArrayList<>(deleteRequest.getMetacards().size());

    for (Metacard metacard : deleteRequest.getMetacards()) {
      LOGGER.debug("File to be deleted: {}", metacard.getId());

      ContentItem deletedContentItem =
          new ContentItemImpl(metacard.getId(), "", null, "", "", 0, metacard);

      if (!ContentItemValidator.validate(deletedContentItem)) {
        LOGGER.warn("Cannot delete invalid content item ({})", deletedContentItem);
        continue;
      }
      try {
        String contentPrefix =
            getContentPrefix(new URI(deletedContentItem.getUri()).getSchemeSpecificPart());

        if (contentPrefix != null
            && amazonS3.listObjectsV2(s3Bucket, contentPrefix).getKeyCount() != 0) {
          deletedContentItems.add(deletedContentItem);
          itemsToBeDeleted.add(metacard);
        }
      } catch (URISyntaxException e) {
        throw new StorageException("Could not delete file: " + metacard.getId(), e);
      }
    }

    deletionMap.put(deleteRequest.getId(), itemsToBeDeleted);

    DeleteStorageResponse response =
        new DeleteStorageResponseImpl(deleteRequest, deletedContentItems);
    LOGGER.trace("EXITING: delete");

    return response;
  }

  @Override
  public void commit(StorageRequest request) throws StorageException {
    if (deletionMap.containsKey(request.getId())) {
      commitDeletes(request);
    } else if (updateMap.containsKey(request.getId())) {
      commitUpdates(request);
    } else {
      LOGGER.info("Nothing to commit for request: {}", request.getId());
    }
  }

  private void commitDeletes(StorageRequest request) throws StorageException {
    List<Metacard> itemsToBeDeleted = deletionMap.get(request.getId());
    try {
      for (Metacard metacard : itemsToBeDeleted) {
        LOGGER.debug("Object to be deleted: {}", metacard.getId());

        String metacardId = metacard.getId();

        String contentPrefix = getContentPrefix(metacardId);

        for (S3ObjectSummary object :
            amazonS3.listObjectsV2(s3Bucket, contentPrefix).getObjectSummaries()) {
          amazonS3.deleteObject(s3Bucket, object.getKey());
        }
      }
    } finally {
      rollback(request);
    }
  }

  private void commitUpdates(StorageRequest request) throws StorageException {
    try {
      for (ContentItem item : updateMap.get(request.getId())) {
        String contentPrefix = getContentPrefix(new URI(item.getUri()).getSchemeSpecificPart());

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(item.getSize());

        amazonS3.putObject(
            s3Bucket, contentPrefix + item.getFilename(), item.getInputStream(), metadata);
      }
    } catch (URISyntaxException | IOException e) {
      throw new StorageException(e);
    } finally {
      rollback(request);
    }
  }

  @Override
  public void rollback(StorageRequest request) {
    String id = request.getId();
    deletionMap.remove(id);
    updateMap.remove(id);
  }

  private ContentItem readContent(URI uri) throws StorageException {
    String contentKey = getContentItemKey(uri);
    String filename = FilenameUtils.getName(contentKey);

    String extension = FilenameUtils.getExtension(filename);
    URI reference = null;

    String mimeType = DEFAULT_MIME_TYPE;
    long size;
    ByteSource byteSource;

    S3Object s3Object = amazonS3.getObject(s3Bucket, contentKey);

    try (InputStream fileInputStream = s3Object.getObjectContent()) {
      mimeType = mimeTypeMapper.guessMimeType(fileInputStream, extension);
    } catch (MimeTypeResolutionException e) {
      LOGGER.debug(
          "Could not determine mime type for file extension = {}; defaulting to {}",
          extension,
          DEFAULT_MIME_TYPE);
    } catch (IOException ie) {
      LOGGER.debug(
          "Error opening stream to external reference {}. Failing StorageProvider read.",
          reference,
          ie);
      throw new StorageException("Cannot read " + reference + ".");
    }

    if (mimeType.equals(DEFAULT_MIME_TYPE)) {
      mimeType = s3Object.getObjectMetadata().getContentType();
    }

    S3Object s3Object2 = amazonS3.getObject(s3Bucket, contentKey);
    InputStream inputStream = s3Object2.getObjectContent();
    try {

      byte[] byteArray = IOUtils.toByteArray(inputStream);
      size = byteArray.length;

      byteSource = ByteSource.wrap(byteArray);

      return new ContentItemImpl(
          uri.getSchemeSpecificPart(),
          uri.getFragment(),
          byteSource,
          mimeType,
          filename,
          size,
          null);
    } catch (IOException e) {
      LOGGER.error(e.getMessage());
    }

    return null;
  }

  private String getContentPrefix(String id) {
    String prefix = contentPrefix;

    if (!contentPrefix.endsWith("/")) {
      prefix = prefix.concat("/");
    }
    prefix = prefix.concat(id.substring(0, 3) + "/" + id.substring(3, 6) + "/" + id + "/");

    return prefix;
  }

  private String getContentItemKey(URI uri) {
    List<S3ObjectSummary> summaries =
        amazonS3
            .listObjects(s3Bucket, getContentPrefix(uri.getSchemeSpecificPart()))
            .getObjectSummaries();

    return summaries.get(0).getKey();
  }

  private ContentItem generateContentItem(ContentItem item) throws IOException {
    LOGGER.trace("ENTERING: generateContentFile");

    ByteSource byteSource;

    InputStream inputStream = item.getInputStream();
    byteSource = ByteSource.wrap(IOUtils.toByteArray(inputStream));

    // See if this item.getFilename matches the filename in readContent
    ContentItemImpl contentItem =
        new ContentItemImpl(
            item.getId(),
            item.getQualifier(),
            byteSource,
            item.getMimeType().toString(),
            item.getFilename(),
            item.getSize(),
            item.getMetacard());

    LOGGER.trace("EXITING: generateContentFile");

    return contentItem;
  }

  private void createAmazonS3() {

    AwsClientBuilder.EndpointConfiguration endpointConfiguration =
        new AwsClientBuilder.EndpointConfiguration(s3Endpoint, s3Region);
    if (org.apache.commons.lang3.StringUtils.isNotBlank(s3AccessKey)) {
      AWSCredentials awsCredentials = new BasicAWSCredentials(s3AccessKey, s3SecretKey);
      AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(awsCredentials);
      amazonS3 =
          AmazonS3ClientBuilder.standard()
              .withCredentials(credentialsProvider)
              .withEndpointConfiguration(endpointConfiguration)
              .build();
    }
    amazonS3 =
        AmazonS3ClientBuilder.standard().withEndpointConfiguration(endpointConfiguration).build();
  }
}
