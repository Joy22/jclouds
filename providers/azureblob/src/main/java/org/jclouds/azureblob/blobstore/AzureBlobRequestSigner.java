/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.azureblob.blobstore;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.URI;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.jclouds.azure.storage.filters.SharedKeyLiteAuthentication;
import org.jclouds.blobstore.BlobRequestSigner;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.functions.BlobToHttpGetOptions;
import org.jclouds.date.DateService;
import org.jclouds.date.TimeStamp;
import org.jclouds.domain.Credentials;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.Uris;
import org.jclouds.http.options.GetOptions;

import com.google.common.base.Supplier;
import com.google.common.net.HttpHeaders;
import com.google.inject.Provider;

@Singleton
public class AzureBlobRequestSigner implements BlobRequestSigner {
   private static final int DEFAULT_EXPIRY_SECONDS = 15 * 60;
   private static final String API_VERSION = "2017-04-17";

   private final String identity;
   private final URI storageUrl;
   private final BlobToHttpGetOptions blob2HttpGetOptions;
   private final Provider<String> timeStampProvider;
   private final DateService dateService;
   private final SharedKeyLiteAuthentication auth;

   @Inject
   public AzureBlobRequestSigner(
         BlobToHttpGetOptions blob2HttpGetOptions, @TimeStamp Provider<String> timeStampProvider,
         DateService dateService, SharedKeyLiteAuthentication auth,
         @org.jclouds.location.Provider Supplier<Credentials> creds)
         throws SecurityException, NoSuchMethodException {
      this.identity = creds.get().identity;
      this.storageUrl = URI.create("https://" + creds.get().identity + ".blob.core.windows.net/");
      this.blob2HttpGetOptions = checkNotNull(blob2HttpGetOptions, "blob2HttpGetOptions");
      this.timeStampProvider = checkNotNull(timeStampProvider, "timeStampProvider");
      this.dateService = checkNotNull(dateService, "dateService");
      this.auth = auth;
   }

   @Override
   public HttpRequest signGetBlob(String container, String name) {
      return signGetBlob(container, name, DEFAULT_EXPIRY_SECONDS);
   }

   @Override
   public HttpRequest signGetBlob(String container, String name, long timeInSeconds) {
      checkNotNull(container, "container");
      checkNotNull(name, "name");
      return sign("GET", container, name, null, timeInSeconds, null);
   }

   @Override
   public HttpRequest signPutBlob(String container, Blob blob) {
      return signPutBlob(container, blob, DEFAULT_EXPIRY_SECONDS);
   }

   @Override
   public HttpRequest signPutBlob(String container, Blob blob, long timeInSeconds) {
      checkNotNull(container, "container");
      checkNotNull(blob, "blob");
      return sign("PUT", container, blob.getMetadata().getName(), null, timeInSeconds,
            blob.getMetadata().getContentMetadata().getContentLength());
   }

   public HttpRequest signRemoveBlob(String container, String name) {
      checkNotNull(container, "container");
      checkNotNull(name, "name");
      return sign("DELETE", container, name, null, DEFAULT_EXPIRY_SECONDS, null);
   }

   @Override
   public HttpRequest signGetBlob(String container, String name, org.jclouds.blobstore.options.GetOptions options) {
      checkNotNull(container, "container");
      checkNotNull(name, "name");
      return sign("GET", container, name, blob2HttpGetOptions.apply(checkNotNull(options, "options")),
            DEFAULT_EXPIRY_SECONDS, null);
   }

   private HttpRequest sign(String method, String container, String name, GetOptions options, long expires, Long contentLength) {
      checkNotNull(method, "method");
      checkNotNull(container, "container");
      checkNotNull(name, "name");

      String nowString = timeStampProvider.get();
      Date now = dateService.rfc1123DateParse(nowString);
      Date expiration = new Date(now.getTime() + TimeUnit.SECONDS.toMillis(expires));
      String iso8601 = dateService.iso8601SecondsDateFormat(expiration);
      String signedPermission;
      if (method.equals("PUT")) {
         signedPermission = "w";
      } else if (method.equals("DELETE")) {
         signedPermission = "d";
      } else {
         signedPermission = "r";
      }

      HttpRequest.Builder request = HttpRequest.builder()
            .method(method)
            .endpoint(Uris.uriBuilder(storageUrl).appendPath(container).appendPath(name).build())
            .replaceHeader(HttpHeaders.DATE, nowString)
            .addQueryParam("sv", API_VERSION)
            .addQueryParam("se", iso8601)
            .addQueryParam("sr", "b")  // blob resource
            .addQueryParam("sp", signedPermission);  // permission

      if (contentLength != null) {
         request.replaceHeader(HttpHeaders.CONTENT_LENGTH, contentLength.toString());
      }

      if (options != null) {
         request.headers(options.buildRequestHeaders());
      }

      if (method.equals("PUT")) {
         request.replaceHeader("x-ms-blob-type", "BlockBlob");
      }

      String stringToSign =
            signedPermission + "\n" +  // signedpermission
            "\n" +  // signedstart
            iso8601 + "\n" +  // signedexpiry
            "/blob/" + identity + "/" + container + "/" + name + "\n" +  // canonicalizedresource
            "\n" + // signedidentifier
            "\n" + // signedIP
            "\n" + // signedProtocol
            API_VERSION + "\n" +  // signedversion
            "\n" +  // rscc
            "\n" +  // rscd
            "\n" +  // rsce
            "\n" +  // rscl
            "";  // rsct

      String signature = auth.calculateSignature(stringToSign);
      request.addQueryParam("sig", signature);
      return request.build();
   }
}
