// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.sample.textract;

import com.amazonaws.sample.textract.StartTextractFunction.Input;
import com.amazonaws.services.comprehend.AmazonComprehend;
import com.amazonaws.services.comprehend.AmazonComprehendClientBuilder;
import com.amazonaws.services.comprehend.model.ClassifyDocumentRequest;
import com.amazonaws.services.comprehend.model.ClassifyDocumentResult;
import com.amazonaws.services.comprehend.model.DocumentClass;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.Block;
import com.amazonaws.services.textract.model.DetectDocumentTextRequest;
import com.amazonaws.services.textract.model.DetectDocumentTextResult;
import com.amazonaws.services.textract.model.Document;
import com.amazonaws.util.IOUtils;
import com.google.gson.GsonBuilder;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static com.amazonaws.sample.textract.StartTextractFunction.getExtension;
import static com.amazonaws.sample.textract.StartTextractFunction.isSupportedFiletype;

/**
 * This function is the first task called by Step Functions. It pulls one page from the PDF, runs Textract, then
 * hits Comprehend, and we return the label.
 * This instance uses Comprehend real time (with an endpoint) but can readily use Batch Comprehend instead. Use the
 * example Textract WAIT_FOR_TASK_TOKEN type for task, with a Lambda trigger on S3 when the Comprehend output is saved.
 * <p>
 * We could split this into discrete SF Tasks - PDF, Textract, Comprehend, if desired.
 */
public class ClassifyFirstPageFunction implements RequestHandler<Map<String, Object>, Map<String, String>> {
    public enum DocumentType {
        APPLICATION, PAYSLIP, BANK, // These correspond to the labels output by Constants.ClassifierArn
        UNKNOWN, NOT_SUPPORTED; // unknown if no match; not_supported if the input file format is not supported
    }

    static final String outputKey = "documentClassification";
    private static final com.google.gson.Gson Gson = new GsonBuilder().create();
    private static final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
    private static final AmazonTextract textract = AmazonTextractClientBuilder.defaultClient();
    private static final AmazonComprehend comprehend = AmazonComprehendClientBuilder.defaultClient();

    // this only does the first page - you could loop on document.getNumberOfpages to get them all
    static File renderPdfToImage(File input, LambdaLogger logger) throws IOException {
        String imageFormat = "jpg";
        ImageType imageType = ImageType.RGB;
        int dpi = 300;
        float quality = 0.95f;
        File output = Files.createTempFile(null, "." + imageFormat).toFile();

        try (PDDocument document = PDDocument.load(input)) {
            PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
            if (acroForm != null && acroForm.getNeedAppearances()) {
                acroForm.refreshAppearances();
            }

            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(false);
            BufferedImage image = renderer.renderImageWithDPI(0, (float) dpi, imageType); // index == page number, just first here
            boolean success = ImageIOUtil.writeImage(image, output.getCanonicalPath(), dpi, quality);

            if (!success)
                throw new IOException("Unknown error rendering image: " + input.toString());
        } catch (IOException e) {
            logger.log("Error parsing PDF file " + input + " + " + e.getMessage());
            throw e;
        }

        return output;
    }

    /**
     * Loads a file into a byte buffer, suitable for SDK byte calls.
     *
     * @throws UncheckedIOException on IOException, to fast fail
     */
    static ByteBuffer loadToBytes(File file) {
        ByteBuffer bytes;
        try (InputStream inputStream = new FileInputStream(file)) {
            bytes = ByteBuffer.wrap(IOUtils.toByteArray(inputStream));
        } catch (IOException e) {
            e.printStackTrace();
            throw new UncheckedIOException(e);
        }
        return bytes;
    }

    static String toPlainText(DetectDocumentTextResult document) {
        // accumulates all the text in each line. Assume that lines are in top -> down / ltr order.
        StringBuilder sb = new StringBuilder();
        for (Block block : document.getBlocks()) {
            if ("LINE".equals(block.getBlockType()))
                sb.append(block.getText()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public Map<String, String> handleRequest(Map<String, Object> input, Context context) {
        LambdaLogger logger = context.getLogger();

        String inputJson = Gson.toJson(input);
        logger.log(inputJson);
        Input event = Gson.fromJson(inputJson, Input.class);

        for (StartTextractFunction.S3Record record : event.input.records) {
            String bucket = record.s3.bucket.name;
            String key = record.s3.object.key;
            key = key.replace('+', ' '); // spaces in filename are getting changed to + in the event.
            String version = record.s3.object.versionId;

            if (!isSupportedFiletype(key)) {
                logger.log("Unsupported file type on object, will not process - " + key);
                continue;
            }

            // We download the PDF and pull out the first page (to an image) and run that against Textract sync
            String extension = getExtension(key);
            File imageFile; // file we send to Textract

            try {
                File s3File = Files.createTempFile(null, "." + extension).toFile();
                GetObjectRequest s3Req = new GetObjectRequest(bucket, key, version);
                logger.log(s3Req.toString());
                ObjectMetadata s3Object = s3.getObject(s3Req, s3File);
                if (s3Object == null) {
                    logger.log("Was not able to find object, bailing");
                    throw new IllegalArgumentException("Object not found");
                }

                if ("pdf".equals(extension)) {
                    // Pull first page from PDF
                    logger.log("Input is PDF, rendering first page...");
                    imageFile = renderPdfToImage(s3File, logger);
                } else {
                    imageFile = s3File; // already an acceptable format.
                }

            } catch (IOException e) {
                logger.log("Error preparing file, will exit " + e.getMessage());
                e.printStackTrace();
                throw new UncheckedIOException(e);
            }

            // Send it to Textract, accumulate the text
            DetectDocumentTextRequest textReq = new DetectDocumentTextRequest()
                .withDocument(new Document().withBytes(loadToBytes(imageFile)));
            logger.log(textReq.toString());
            DetectDocumentTextResult textResult = textract.detectDocumentText(textReq);
            String plainText = toPlainText(textResult);

            // Call Comprehend Classifier:
            ClassifyDocumentRequest classifyReq = new ClassifyDocumentRequest()
                .withEndpointArn(System.getenv(Constants.EnvComprehendClassifier))
                .withText(plainText);
            logger.log(classifyReq.toString());
            ClassifyDocumentResult classifyResult = comprehend.classifyDocument(classifyReq);
            logger.log(classifyResult.toString());

            List<DocumentClass> classes = classifyResult.getClasses(); // will be sorted best => worst match
            String classification = DocumentType.UNKNOWN.name();
            if (classes.size() > 0) {
                DocumentClass documentClass = classes.get(0);
                if (documentClass.getScore() > 0.5)
                    classification = documentClass.getName(); // not DocumentType casting, to support optional other labels later
            }

            return Map.of(outputKey, classification); // step functions configured to insert this in output plus input data

            // completes on first supported file type in event
        }

        return Map.of(outputKey, DocumentType.NOT_SUPPORTED.name());
    }


}
