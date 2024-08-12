package org.example.dags.realestate;

import org.apache.beam.io.requestresponse.RequestResponseIO;
import org.apache.beam.io.requestresponse.Result;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.*;
import org.apache.commons.csv.CSVRecord;
import org.example.dags.webapi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.zip.ZipInputStream;

public class ExtractZipContentsVertices {
    static Logger LOG = LoggerFactory.getLogger(ExtractZipContentsVertices.class);

    public static final TupleTag<ResidentialLandTxn> residentialLand = new TupleTag<>() {};
    public static final TupleTag<UsedApartmentTxn> usedApartment = new TupleTag<>() {};

    static class UnzipFn extends DoFn<KV<String, WebApiHttpResponse>, ResidentialLandTxn> {
        /**
         *
         * @param c
         * @param out
         * @throws IOException
         * @throws Exception
         */
        @ProcessElement
        public void processElement(ProcessContext c, MultiOutputReceiver out) throws IOException, Exception {
            KV<String, WebApiHttpResponse> elem = c.element();
            ByteArrayInputStream bis = new ByteArrayInputStream(elem.getValue().getData());
            ZipInputStream zs = new ZipInputStream(bis);
            RealEstateCsv realEstateCsv = RealEstateCsv.of(zs);

            // Flatten
            for(CSVRecord record : realEstateCsv.records) {
                switch (realEstateCsv.dlEndpoint) {
                    case EndpointKind.RESIDENTIAL_LAND:
                        out.get(residentialLand).output(ResidentialLandTxn.of(record));
                        break;
                    case EndpointKind.USED_APARTMENT:
                        out.get(usedApartment).output(UsedApartmentTxn.of(record));
                        break;
                    case EndpointKind.LAND_VALUE:
                        break;
                    default:
                        throw new IllegalStateException("Unknown endpoint: " + realEstateCsv.dlEndpoint);
                }
            }

            zs.close();
            bis.close();
         }
    }

    public static class Extract extends PTransform<PCollection<String>, PCollectionTuple> {
        /**
         *
         * @param input
         * @return
         */
        @Override
        public PCollectionTuple expand(PCollection<String> input) {
            KvCoder<String, WebApiHttpResponse> respCoder = KvCoder.of(
                    StringUtf8Coder.of(), WebApiHttpResponseCoder.of());

            PCollection<WebApiHttpRequest> requests = input
                    .apply("ToWebApiHttpRequest",
                            MapElements
                                    .into(TypeDescriptor.of(WebApiHttpRequest.class))
                                    .via(WebApiHttpRequest::of))
                    .setCoder(WebApiHttpRequestCoder.of());

            Result<KV<String, WebApiHttpResponse>> results = requests
                    .apply("DownloadZipFiles",
                            RequestResponseIO.of(WebApiHttpClient.of(), respCoder));

            return results
                    .getResponses()
                    .apply("ToEntites", ParDo.of(new ExtractZipContentsVertices.UnzipFn())
                            .withOutputTags(residentialLand, TupleTagList.of(usedApartment)));
        }
    }
}
