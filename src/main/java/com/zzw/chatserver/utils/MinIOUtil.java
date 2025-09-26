package com.zzw.chatserver.utils;

import io.minio.*;
import io.minio.http.Method;
import org.csource.common.MyException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.UUID;

@Component
public class MinIOUtil {
    private MinioClient minioClient;
    @Value("${minio.endpoint}")
    private String endpoint;       // MinIO地址
    @Value("${minio.access-key}")
    private String accessKey;      // MinIO访问密钥
    @Value("${minio.secret-key}")
    private String secretKey;      // MinIO秘密密钥
    @Value("${minio.bucket-name}")
    private String bucketName;     // MinIO存储桶
    @Value("${minio.expire}")
    private int expire; // 注意类型是int，与配置值一致

    // 初始化MinIO客户端
    @PostConstruct
    public void initMinioClient() {
        // expire的合法性校验
        if (expire <= 0) {
            throw new RuntimeException("MinIO URL过期时间配置无效（expire必须大于0）");
        }
        System.out.println("当前MinIO API地址：" + endpoint); // 此时endpoint已注入，不会为null
        try {
            this.minioClient = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("MinIO客户端初始化失败（注入后）：" + e.getMessage(), e);
        }
    }

    // uploadFile(MultipartFile)：返回值/参数/异常完全对齐FastDFS
    public String uploadFile(MultipartFile file) throws IOException, MyException {
        try {
            if (file.isEmpty()) {
                throw new MyException("上传文件不能为空");
            }

            String originalFilename = file.getOriginalFilename();
            // 提取文件扩展名
            String fileExt = originalFilename.substring(originalFilename.lastIndexOf(".") + 1);
            // 文件ID格式：bucket名/日期/UUID.扩展名
            String dateDir = Instant.now().toString().substring(0, 10); // 取当前日期（如2024-05-20）
            String fileName = UUID.randomUUID().toString() + "." + fileExt;
            String fileId = bucketName + "/" + dateDir + "/" + fileName; // 格式：chatserver/2024-05-20/xxx.jpg

            // 上传文件到MinIO（group+路径存储）
            try (InputStream inputStream = file.getInputStream()) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(dateDir + "/" + fileName) // MinIO内路径：日期/文件名
                                .stream(inputStream, file.getSize(), -1)
                                .contentType(file.getContentType())
                                .build()
                );
            }

            // 返回"文件ID"格式（如：chatserver/2024-05-20/xxx.jpg）
            return fileId;
        } catch (Exception e) {
            if (e instanceof MyException) {
                throw (MyException) e;
            }
            throw new MyException("文件上传失败：" + e.getMessage());
        }
    }

    // downloadFile(String)
    public byte[] downloadFile(String fileId) throws IOException, MyException {
        try {
            if (fileId == null || fileId.isEmpty()) {
                throw new MyException("文件ID不能为空");
            }

            // 提取MinIO内的路径（如chatserver/2024-05-20/xxx.jpg → 2024-05-20/xxx.jpg）
            String minioObjectKey = fileId.startsWith(bucketName + "/")
                    ? fileId.substring(bucketName.length() + 1)
                    : fileId;

            // 从MinIO下载文件
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(minioObjectKey)
                            .build()
            );
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = stream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                return outputStream.toByteArray();
            }
        } catch (Exception e) {
            if (e instanceof MyException) {
                throw (MyException) e;
            }
            throw new MyException("文件下载失败：" + e.getMessage());
        }
    }

    // uploadFile(String)：本地文件上传
    public String uploadFile(String localFilePath) throws IOException, MyException {
        try {
            if (localFilePath == null || localFilePath.isEmpty()) {
                throw new MyException("本地文件路径不能为空");
            }

            // 提取本地文件名和扩展名
            String fileName = localFilePath.substring(localFilePath.lastIndexOf("/") + 1);
            if (fileName.lastIndexOf(".") == -1) {
                throw new MyException("无效的文件名（缺少扩展名）");
            }
            String fileExt = fileName.substring(fileName.lastIndexOf(".") + 1);

            String dateDir = Instant.now().toString().substring(0, 10);
            String newFileName = UUID.randomUUID().toString() + "." + fileExt;
            String fileId = bucketName + "/" + dateDir + "/" + newFileName;

            // 上传本地文件到MinIO
            minioClient.uploadObject(
                    UploadObjectArgs.builder()
                            .bucket(bucketName)
                            .object(dateDir + "/" + newFileName)
                            .filename(localFilePath)
                            .build()
            );

            return fileId;
        } catch (Exception e) {
            if (e instanceof MyException) {
                throw (MyException) e;
            }
            throw new MyException("本地文件上传失败：" + e.getMessage());
        }
    }

    // 生成MinIO原生预签名URL（简化实现，使用MinIO自带的签名机制）
    public String getFileUrl(String fileId) throws MyException {
        try {
            if (fileId == null || fileId.isEmpty()) {
                throw new MyException("文件ID不能为空");
            }

            // 解析文件ID：格式应为 "bucketName/objectPath"（如"chatserver/2024-05-20/xxx.jpg"）
            String[] fileIdParts = fileId.split("/", 2);
            if (fileIdParts.length != 2) {
                throw new MyException("无效的文件ID格式，应为'bucket/object'");
            }
            String bucket = fileIdParts[0];
            String object = fileIdParts[1];

            // 使用MinIO原生方法生成预签名URL（自动处理签名，无需手动生成token）
            // expire参数直接使用配置的过期时间（3600秒）
            String presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .object(object)
                            .expiry(expire)  // 从配置文件读取的过期时间（3600秒）
                            .build()
            );

            // 直接返回MinIO生成的完整预签名URL
            return presignedUrl;
        } catch (Exception e) {
            // 统一转换为自定义异常，简化上层处理
            throw new MyException("生成文件访问URL失败：" + e.getMessage());
        }
    }
}