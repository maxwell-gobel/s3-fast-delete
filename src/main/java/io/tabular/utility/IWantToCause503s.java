package example.utility;

import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Publisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class IWantToCause503s {

    private static final Integer maxKeys = 1000;

    private static S3AsyncClient getClient() {
        return S3AsyncClient.builder()
                .httpClient(NettyNioAsyncHttpClient.create())
                .build();
    }


    private static void deletePrefix(String bucket, String prefix) throws ExecutionException, InterruptedException {
        S3AsyncClient s3 = getClient();

        List<CompletableFuture<DeleteObjectsResponse>> deleteFutures =
                Collections.synchronizedList(new ArrayList<>());

        ListObjectsV2Request listRequest =
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(maxKeys).build();

        ListObjectsV2Publisher publisher = s3.listObjectsV2Paginator(listRequest);

        publisher.subscribe(listing -> {
            if (!listing.hasContents()) {
                System.out.printf("No objects to delete for %s", listing.prefix());
                return;
            }

            List<ObjectIdentifier> objectIdentifiers =
                    listing.contents().stream()
                            .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                            .toList();

            DeleteObjectsRequest deleteRequest =
                    DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(objectIdentifiers).build())
                            .build();

            deleteFutures.add(
                    s3.deleteObjects(deleteRequest)
                            .whenComplete(
                                    (r, e) -> {
                                        if (e != null) {
                                            System.out.printf("Error deleting %s/%s", bucket, prefix);
                                        }
                                        System.out.printf("Successfully deleted %s objects%n", r.deleted().size());
                                    }));
            long completed = deleteFutures.stream().filter(CompletableFuture::isDone).count();

            System.out.printf("%d / %d%n", completed, deleteFutures.size());
        }).thenCompose(
                unused -> CompletableFuture.allOf(deleteFutures.toArray(new CompletableFuture[0]))
        ).whenComplete((r, e) -> {
            System.out.printf("Completed running delete on %s/%s%n", bucket, prefix);
        }).get();
    }

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        if (args.length != 2) {
            System.out.println("Usage: java -jar $JAR $BUCKET $PREFIX");
            return;
        }
        deletePrefix(args[0], args[1]);
    }
}
