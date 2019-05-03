/*
 * Copyright (c) 2018-2019 VMware, Inc. All rights reserved. VMware Confidential
 */

package com.vmware.blockchain.services.contracts;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.vmware.blockchain.common.BadRequestException;
import com.vmware.blockchain.common.ErrorCode;
import com.vmware.blockchain.common.restclient.RestClientBuilder;

/**
 * A compiler class which allows compiling solidity contract source codes.
 */
public class Compiler {

    private static final Logger logger = LogManager.getLogger(Compiler.class);

    /**
     * Creates a temporary directory and creates a solidity contract source code file inside that directory. The output
     * files generated by the compiler will also be present in same temporary directory
     *
     * <p>@param contents solidity source code
     * @return The path of the created source file.
     */
    private static Path createSourceFiles(String contents) throws IOException {
        // Create a random directory
        // Need execute permissions to `cd` into that directory
        String permissions = "rwxrwxrwx";
        String directoryPrefix = "helen-solc";
        String sourceFileName = "source.sol";
        FileAttribute<Set<PosixFilePermission>> attr =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions));
        Path workDir = Files.createTempDirectory(directoryPrefix, attr);
        // Note: Ideally we should name this file from the name of the
        // contract, i.e if contract is `ContractVersion Manager {..` then we
        // should name file as `Manager.sol` however that will require
        // parsing of the `contents` string. Instead we create a source file
        // with generic name and let the compiler compile this file.
        // compiler will actually parse the source code and generate output
        // files with proper names (Manager.bin, Manager_meta.json etc)
        // We can then use these file names to deduce the name of contract
        Path sourceFile = workDir.resolve(sourceFileName);
        Files.createFile(sourceFile, attr);
        try (BufferedWriter writer = Files.newBufferedWriter(sourceFile, Charset.defaultCharset())) {
            writer.write(contents);
        }
        return sourceFile;
    }

    /**
     * Recursively deletes the given directory tree rooted at `root`.
     *
     * @param root The Path of root directory which should be deleted.
     */
    private static void deleteDirectoryTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        Files.delete(dir);
                    } else {
                        logger.warn("Exception while deleting: " + dir, exc);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            logger.warn("Exception while deleting directory tree starting at: " + root, e);
        }
    }

    /**
     * Checks if the given Path represents a solidity compiler generated bytecode file. Currently the extension of
     * solidity compiler generated bytecode file is `.bin`.
     *
     * @param file Path of the bytecode file
     * @return returns true if file is a bytecode file, false otherwise
     */
    private static boolean isBytecodeFile(Path file) {
        return file.toString().endsWith(".bin");
    }

    /**
     * Checks if the given Path represents a solidity compiler generated metadata file. Currently solidity compiler
     * appends `_meta` to the name of the contract and then generates a metadata file with name as
     * `[contract_name]_meta.json`. Here we simply check if given path ends in `_meta.json`.
     *
     * @param file The Path of the metadata file
     * @return Returns true if file is a metadata file, false otherwise
     */
    private static boolean isMetadataFile(Path file) {
        return file.toString().endsWith("_meta.json");
    }

    /**
     * Extracts the name of the contract from given Path of the bytecode file ( [ContractName].bin) or from the given
     * Path of the metadata ([ContractName]_meta.json) file.
     *
     * @param file Path of the bytecode or metadata file
     * @return Name of the contract
     */
    private static String extractContractName(Path file) {
        // If its a bytecode file then its name will be of the form
        // <ContractName>.bin. If its a metadata file then its name will be of
        // the form <ContractName>_meta.json
        String fileName = file.getFileName().toString();
        if (isBytecodeFile(file)) {
            return fileName.substring(0, fileName.lastIndexOf("."));
        } else if (isMetadataFile(file)) {
            return fileName.substring(0, fileName.lastIndexOf("_meta.json"));
        } else {
            throw new BadRequestException(ErrorCode.BYTECODE_OR_METADATA_ALLOWED);
        }
    }

    /**
     * Reads all contents of a file into a string. Note: The contents are first read into a byte array and then
     * converted into a string by using systems default charset.
     *
     * @param file Path of the file
     * @return Returns a string containing contents of the file
     */
    private static String readFileContents(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return new String(bytes);
    }

    /**
     * Reads all the byteCode files in given directory and returns a Map with key as the name of the contract and value
     * as the contents of its bytecode (one map entry per bytecode file).
     *
     * @param dir The directory from which bytecode files should be read
     * @return The map of key-value pairs of contract name and its bytecode
     */
    private static Map<String, String> getCompiledByteCodeFrom(Path dir)
            throws IOException, DirectoryIteratorException {
        Map<String, String> byteCodeMap = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (isBytecodeFile(file)) {
                    byteCodeMap.put(extractContractName(file), readFileContents(file));
                }
            }
        }
        return byteCodeMap;
    }

    /**
     * Reads all the metadata files in given directory and returns a Map with key as the name of the contract and value
     * as the contents of its metadata. There is one map entry per metadata file.
     *
     * @param dir The directory from which metadata files should be read
     * @return The map of key-value pairs of contract name and its metadata
     */
    private static Map<String, String> getMetadataFrom(Path dir) throws IOException, DirectoryIteratorException {
        Map<String, String> metadataMap = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (isMetadataFile(file)) {
                    metadataMap.put(extractContractName(file), readFileContents(file));
                }
            }
        }
        return metadataMap;
    }

    /**
     * Reads the entire data available in the given input stream.
     *
     * @param is The input stream from which data should be read
     * @return Returns a string containing all the data of given input stream
     */
    private static String readStream(InputStream is) throws IOException {
        String line;
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Compiles the given solidity contract code and returns the result of compilation. This method invokes the
     * pre-installed `solc` command. Hence, we first have to write the give solidity contract code into a file which we
     * then pass to `solc` as a command line argument.
     *
     * @param solidityCode The string containing solidity source code
     * @return The result object containing compilation result.
     */
    public static Result compile(String solidityCode, String compilerVersion, String compileUrl,
            boolean isOptimize, int runs) {
        Result result = new Result();
        RestTemplate restTemplate = new RestClientBuilder().withBaseUrl(compileUrl)
                                                           .withNoObjectMapper()
                                                           .build();

        /*
          FIXME(huh): When the solidity file contains comments(.e.g "//" or "/* ... "),
          if adding 'headers.add("Content-Type", "application/json")', the solidity
          compiler service will throw 500 error, and it works well without this header.
          We haven't figure out the root cause yet.
        */
        HttpHeaders headers = new HttpHeaders();
        // headers.add("Content-Type", "application/json");

        Map<String, Object> body = new HashMap<String, Object>();
        body.put("compilerVersion", compilerVersion);
        body.put("sourcecode", solidityCode);
        body.put("isOptimize", isOptimize);
        body.put("runs", runs);
        ResponseEntity<String> response
            = restTemplate.exchange("/compile",
                                    HttpMethod.POST,
                                    new HttpEntity<>(body, headers),
                                    String.class);
        try {
            if (response.getStatusCodeValue() == 200) {
                result.setSuccess(true);
                String responseBody = response.getBody();
                JSONParser parser = new JSONParser();
                Map<String, JSONArray> jsonMap  = (Map<String, JSONArray>) parser.parse(responseBody);
                JSONArray jsonArray = jsonMap.get("data");
                Map<String, String> bytecodeMap = new HashMap<String, String>();
                Map<String, String> metadataMap = new HashMap<String, String>();
                for (Object element : jsonArray) {
                    JSONObject json = (JSONObject) element;
                    bytecodeMap.put(json.get("contract_name").toString(),
                                    json.get("bytecode").toString());
                    metadataMap.put(json.get("contract_name").toString(),
                                    json.get("metadata").toString());
                }
                result.setByteCodeMap(bytecodeMap);
                result.setMetadataMap(metadataMap);
            }
        } catch (ParseException e) {
            logger.warn("Error in parsed compile response", e);
        } catch (RestClientException e) {
            result.setStderr("Compiling smart contract failed :"
                + e.getMessage());
        }
        return result;
    }

    /**
     * Verify the given solidity contract code and returns the result of verification.
     *
     * @param solidityCode The string containing solidity source code
     * @param compilerVersion The version of solidity compiler to be verified
     * @param existingBytecode The string containing existing solidity bytecode
     * @param selectedContract The name of the solidity contract to be verified
     *
     * @return returns true if solidity contract is verified.
     */
    public static boolean verify(String solidityCode, String compilerVersion, String compileUrl,
            String existingBytecode, String selectedContract, boolean isOptimize, int runs) {
        RestTemplate restTemplate = new RestClientBuilder().withBaseUrl(compileUrl)
                .withNoObjectMapper().build();
        HttpHeaders headers = new HttpHeaders();
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("compilerVersion", compilerVersion);
        body.put("sourcecode", solidityCode);
        body.put("existingBytecode", existingBytecode);
        body.put("selectedContract", selectedContract);
        body.put("isOptimize", isOptimize);
        body.put("runs", runs);
        ResponseEntity<String> response = restTemplate.exchange("/verify", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        try {
            if (response.getStatusCodeValue() == 200) {
                String responseBody = response.getBody();
                JSONParser parser = new JSONParser();
                Map<String, JSONObject> jsonMap = (Map<String, JSONObject>) parser.parse(responseBody);
                JSONObject result = jsonMap.get("data");
                return (boolean) result.get("verified");
            }
        } catch (ParseException e) {
            logger.warn("Error in parsed verify response", e);
        }
        return false;
    }

    /**
     * Get solidity compiler versions form the solidity compiler service.
     *
     * @param compileUrl The solidity compiler service url.
     * @return The result object containing compilation result.
     */
    public static JSONArray getCompilerVersions(String compileUrl) {
        RestTemplate restTemplate
            = new RestClientBuilder().withBaseUrl(compileUrl)
                                     .withNoObjectMapper()
                                     .build();
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<String> response
            = restTemplate.exchange("/compiler_versions",
                                    HttpMethod.GET,
                                    new HttpEntity<>(headers),
                                    String.class);
        try {
            JSONArray jsonArray = new JSONArray();
            JSONParser parser = new JSONParser();
            jsonArray = (JSONArray) parser.parse(response.getBody());
            return jsonArray;
        } catch (ParseException e) {
            logger.warn("Error in getting solidity compiler versions", e);
        }
        return null;
    }

    /**
     * A simple POJO class representing the Result of solidity compilation.
     */
    public static class Result {
        // compilation success or failure
        boolean success;
        // standard output of compilation
        String stdout;
        // standard error of compilation
        String stderr;
        // Bytecode of the compiled contract, if successful
        Map<String, String> byteCodeMap;
        // Metadata of the compiled contract, if successful
        Map<String, String> metadataMap;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getStdout() {
            return stdout;
        }

        public void setStdout(String stdout) {
            this.stdout = stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public void setStderr(String stderr) {
            this.stderr = stderr;
        }

        public Map<String, String> getByteCodeMap() {
            return byteCodeMap;
        }

        public void setByteCodeMap(Map<String, String> byteCodeMap) {
            this.byteCodeMap = byteCodeMap;
        }

        public Map<String, String> getMetadataMap() {
            return metadataMap;
        }

        public void setMetadataMap(Map<String, String> metadataMap) {
            this.metadataMap = metadataMap;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Compilation Success: ").append(success).append("\n").append("stdout: ").append(stdout)
                    .append("\n").append("stderr: ").append(stderr).append("\n");
            if (byteCodeMap != null && metadataMap != null) {
                for (Map.Entry<String, String> e : byteCodeMap.entrySet()) {
                    sb.append(e.getKey() + "=> [" + e.getValue() + "]");
                }
                for (Map.Entry<String, String> e : metadataMap.entrySet()) {
                    sb.append(e.getKey() + "=> [" + e.getValue() + "]");
                }
            }
            return sb.toString();
        }
    }

}
