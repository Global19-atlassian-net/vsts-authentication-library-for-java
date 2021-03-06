// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.alm.storage.macosx;


import com.microsoft.alm.helpers.Func;
import com.microsoft.alm.helpers.IOHelper;
import com.microsoft.alm.helpers.StringHelper;
import com.microsoft.alm.oauth2.useragent.subprocess.DefaultProcessFactory;
import com.microsoft.alm.oauth2.useragent.subprocess.ProcessCoordinator;
import com.microsoft.alm.oauth2.useragent.subprocess.TestableProcess;
import com.microsoft.alm.oauth2.useragent.subprocess.TestableProcessFactory;
import com.microsoft.alm.secret.Credential;
import com.microsoft.alm.secret.Token;
import com.microsoft.alm.secret.TokenPair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class KeychainSecurityCliStore {

    static final String SECURITY = "/usr/bin/security";
    static final String DELETE_GENERIC_PASSWORD = "delete-generic-password";
    static final String FIND_GENERIC_PASSWORD = "find-generic-password";
    static final String ADD_GENERIC_PASSWORD = "add-generic-password";
    static final String SHOW_KEYCHAIN_INFO = "show-keychain-info";
    static final String ACCOUNT_PARAMETER = "-a";
    static final String ACCOUNT_METADATA = "acct";
    static final String PASSWORD = "password";
    private static final String SERVICE_PARAMETER = "-s";
    private static final String KIND_PARAMETER = "-D";
    private static final String PASSWORD_PARAMETER = "-w";
    private static final String UPDATE_IF_ALREADY_EXISTS = "-U";
    private static final int ITEM_NOT_FOUND_EXIT_CODE = 44;
    private static final int USER_INTERACTION_NOT_ALLOWED_EXIT_CODE = 36;
    private static final String INTERACTIVE_MODE = "-i";

    protected boolean deleteByKind(final String targetName, final SecretKind kind) {
        try {
            final TestableProcess process = processFactory.create(
                    SECURITY,
                    DELETE_GENERIC_PASSWORD,
                    SERVICE_PARAMETER, targetName,
                    KIND_PARAMETER, kind.name()
            );
            // we don't care about the exit code
            process.waitFor();

            return true;
        } catch (final IOException e) {
            throw new Error(e);
        } catch (final InterruptedException e) {
            throw new Error(e);
        }
    }

    private static final Func<String, String> QUOTING_PROCESSOR = new Func<String, String>() {
        @Override
        public String call(final String s) {
            if (s.contains(" ")) {
                return '"' + s + '"';
            }
            return s;
        }
    };

    enum SecretKind {
        Credential,
        Token,
        TokenPair_Access_Token,
        TokenPair_Refresh_Token;
    }

    private final TestableProcessFactory processFactory;

    public KeychainSecurityCliStore() {
        this(new DefaultProcessFactory());
    }

    KeychainSecurityCliStore(final TestableProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    static Map<String, Object> parseKeychainMetaData(final String metadata) {
        final Map<String, Object> result = new HashMap<String, Object>();
        parseKeychainMetaData(metadata, result);
        return result;
    }

    static void parseKeychainMetaData(final String metadata, final Map<String, Object> result) {
        final StringReader sr = new StringReader(metadata);
        final BufferedReader br = new BufferedReader(sr);
        boolean parsingAttributes = false;
        String line;
        try {
            while ((line = br.readLine()) != null) {
                if (parsingAttributes) {
                    parseAttributeLine(line, result);
                } else {
                    if ("attributes:".equals(line)) {
                        parsingAttributes = true;
                    } else {
                        parseMetadataLine(line, result);
                    }
                }
            }
        } catch (final IOException e) {
            throw new Error(e);
        } finally {
            IOHelper.closeQuietly(br);
        }
    }

    private static final Pattern MetadataLinePattern = Pattern.compile
        (
            //   ^(\w+):\s"(.+)"
            "^(\\w+):\\s\"(.+)\""
        );

    static void parseMetadataLine(final String line, final Map<String, Object> destination) {
        final Matcher matcher = MetadataLinePattern.matcher(line);
        if (matcher.matches()) {
            final String key = matcher.group(1);
            final String value = matcher.group(2);
            destination.put(key, value);
        }
    }

    enum AttributeParsingState {
        Spaces,
        StringKey,
        HexKey,
        BeforeType,
        Type,
        AfterType,
        BeforeValue,
        NullValue,
        StringValue,
        TimeDateValue,
        ValueFinished,;
    }

    static void parseAttributeLine(final String line, final Map<String, Object> destination) {
        final String template = "Undefined transition '%1$s' from %2$s.";
        final StringBuilder key = new StringBuilder();
        final StringBuilder type = new StringBuilder();
        final StringBuilder value = new StringBuilder();
        boolean isNullValue = false;
        AttributeParsingState state = AttributeParsingState.Spaces;
        for (final char c : line.toCharArray()) {
            switch (state) {
                case Spaces:
                    switch (c) {
                        case ' ':
                            break;
                        case '0':
                            state = AttributeParsingState.HexKey;
                            key.append(c);
                            break;
                        case '"':
                            state = AttributeParsingState.StringKey;
                            break;
                        default:
                            throw new Error(String.format(template, c, state));
                    }
                    break;
                case HexKey:
                    switch (c) {
                        case ' ':
                            state = AttributeParsingState.BeforeType;
                            break;
                        case 'x':
                        case '0':
                        case '1':
                        case '2':
                        case '3':
                        case '4':
                        case '5':
                        case '6':
                        case '7':
                        case '8':
                        case '9':
                        case 'A':
                        case 'B':
                        case 'C':
                        case 'D':
                        case 'E':
                        case 'F':
                            key.append(c);
                            break;
                        default:
                            throw new Error(String.format(template, c, state));
                    }
                    break;
                case StringKey:
                    switch (c) {
                        case '"':
                            state = AttributeParsingState.BeforeType;
                            break;
                        default:
                            key.append(c);
                            break;
                    }
                    break;
                case BeforeType:
                    switch (c) {
                        case '<':
                            state = AttributeParsingState.Type;
                            break;
                        default:
                            throw new Error(String.format(template, c, state));
                    }
                    break;
                case Type:
                    switch (c) {
                        case '>':
                            state = AttributeParsingState.AfterType;
                            break;
                        default:
                            type.append(c);
                            break;
                    }
                    break;
                case AfterType:
                    switch (c) {
                        case '=':
                            state = AttributeParsingState.BeforeValue;
                            break;
                        default:
                            throw new Error(String.format(template, c, state));
                    }
                    break;
                case BeforeValue:
                    switch (c) {
                        case '<':
                            state = AttributeParsingState.NullValue;
                            isNullValue = true;
                            value.append(c);
                            break;
                        case '0':
                            // TODO: check that type was "timedate"
                            state = AttributeParsingState.TimeDateValue;
                            value.append(c);
                            break;
                        case '"':
                            state = AttributeParsingState.StringValue;
                            break;
                        default:
                            throw new Error(String.format(template, c, state));
                    }
                    break;
                case NullValue:
                    switch (c) {
                        case '>':
                            state = AttributeParsingState.ValueFinished;
                            value.append(c);
                            break;
                        case 'N':
                        case 'U':
                        case 'L':
                            value.append(c);
                            break;
                        default:
                            throw new Error(String.format(template, c, state));
                    }
                    break;
                case StringValue:
                    // double quotes aren't escaped, so everything goes in as-is
                    value.append(c);
                    break;
                case TimeDateValue:
                    // we don't care about timedate for now, so just append as-is
                    value.append(c);
                    break;
                case ValueFinished:
                    throw new Error(String.format(template, c, state));
            }
        }
        if (isNullValue) {
            destination.put(key.toString(), null);
        } else if ("blob".equals(type.toString())) {
            final int lastCharIndex = value.length() - 1;
            value.deleteCharAt(lastCharIndex);
            destination.put(key.toString(), value.toString());
        }
        // TODO: else if ("timedate".equals(type))
        // TODO: else if ("uint32".equals(type))
        // TODO: else if ("sint32".equals(type))
    }

    public boolean isKeychainAvailable() {
        final String stdOut, stdErr;
        try {
            final TestableProcess process = processFactory.create(
                SECURITY,
                SHOW_KEYCHAIN_INFO
            );
            final ProcessCoordinator coordinator = new ProcessCoordinator(process);
            final int result = coordinator.waitFor();
            stdOut = coordinator.getStdOut();
            stdErr = coordinator.getStdErr();
            checkResult(result, stdOut, stdErr);
        } catch (final IOException e) {
            throw new Error(e);
        } catch (final InterruptedException e) {
            throw new Error(e);
        } catch (final SecurityException e) {
            return false;
        }
        return true;
    }



    static void checkResult(final int result, final String stdOut, final String stdErr) {
        if (result != 0) {
            if (result == USER_INTERACTION_NOT_ALLOWED_EXIT_CODE) {
                throw new SecurityException("User interaction is not allowed.");
            } else {
                final String template = "%1$s exited with result %2$d.\nstdOut: %3$s\nstdErr: %4$s\n";
                final String message = String.format(template, SECURITY, result, stdOut, stdErr);
                throw new Error(message);
            }
        }
    }

    static Map<String, Object> read(final SecretKind secretKind, final TestableProcessFactory processFactory, final String serviceName) {
        final String stdOut, stdErr;
        try {
            final TestableProcess process = processFactory.create(
                SECURITY,
                FIND_GENERIC_PASSWORD,
                SERVICE_PARAMETER, serviceName,
                KIND_PARAMETER, secretKind.name(),
                "-g" // "Display the password for the item found"
            );
            final ProcessCoordinator coordinator = new ProcessCoordinator(process);
            final int result = coordinator.waitFor();
            stdOut = coordinator.getStdOut();
            stdErr = coordinator.getStdErr();
            if (result != 0 && result != ITEM_NOT_FOUND_EXIT_CODE) {
                checkResult(result, stdOut, stdErr);
            }
        } catch (final IOException e) {
            throw new Error(e);
        } catch (final InterruptedException e) {
            throw new Error(e);
        }

        final Map<String, Object> metaData = parseKeychainMetaData(stdOut);
        parseKeychainMetaData(stdErr, metaData);

        return metaData;
    }

    public Credential readCredentials(final String targetName) {
        final Map<String, Object> metaData = read(SecretKind.Credential, processFactory, targetName);

        final Credential result;
        if (metaData.size() > 0) {
            final String userName = (String) metaData.get(ACCOUNT_METADATA);
            final String password = (String) metaData.get(PASSWORD);

            result = new Credential(userName, password);
        } else {
            result = null;
        }

        return result;
    }

    public Token readToken(final String targetName) {
        final Map<String, Object> metaData = read(SecretKind.Token, processFactory, targetName);

        final Token result;
        if (metaData.size() > 0) {
            final String typeName = (String) metaData.get(ACCOUNT_METADATA);
            final String password = (String) metaData.get(PASSWORD);

            result = new Token(password, typeName);
        } else {
            result = null;
        }

        return result;
    }

    public TokenPair readTokenPair(final String targetName) {
        String accessToken, refreshToken;

        final Map<String, Object> accessTokenMetaData = read(SecretKind.TokenPair_Access_Token, processFactory, targetName);

        if (accessTokenMetaData.size() > 0) {
            final String password = (String) accessTokenMetaData.get(PASSWORD);
            accessToken = password;
        } else {
            accessToken = null;
        }

        final Map<String, Object> refreshTokenMetaData = read(SecretKind.TokenPair_Refresh_Token, processFactory, targetName);

        if (refreshTokenMetaData.size() > 0) {
            final String password = (String) refreshTokenMetaData.get(PASSWORD);
            refreshToken = password;
        } else {
            refreshToken = null;
        }

        if (accessToken != null && refreshToken != null) {
            return new TokenPair(accessToken, refreshToken);
        }

        return null;
    }

    static void write(final SecretKind secretKind, final TestableProcessFactory processFactory, final String serviceName, final String accountName, final String password) {
        final String stdOut, stdErr;
        try {
            final TestableProcess addProcess = processFactory.create(
                SECURITY,
                INTERACTIVE_MODE
            );
            final String[] commandParts = {
                ADD_GENERIC_PASSWORD,
                UPDATE_IF_ALREADY_EXISTS,
                ACCOUNT_PARAMETER, accountName,
                SERVICE_PARAMETER, serviceName,
                PASSWORD_PARAMETER, password,
                KIND_PARAMETER, secretKind.name()
            };
            final ProcessCoordinator coordinator = new ProcessCoordinator(addProcess);
            final String command = StringHelper.join(" ", commandParts, 0, commandParts.length, QUOTING_PROCESSOR);
            coordinator.println(command);
            final int result = coordinator.waitFor();
            stdOut = coordinator.getStdOut();
            stdErr = coordinator.getStdErr();
            checkResult(result, stdOut, stdErr);
        } catch (final IOException e) {
            throw new Error(e);
        } catch (final InterruptedException e) {
            throw new Error(e);
        }
    }

    public void writeCredential(final String targetName, final Credential credentials) {
        write(SecretKind.Credential, processFactory, targetName, credentials.Username, credentials.Password);
    }

    public void writeToken(final String targetName, final Token token) {
        writeTokenKind(targetName, SecretKind.Token, token);
    }

    private void writeTokenKind(final String targetName, final SecretKind secretKind, final Token token) {
        final AtomicReference<String> accountNameReference = new AtomicReference<String>();
        Token.getFriendlyNameFromType(token.Type, accountNameReference);
        final String accountName = accountNameReference.get();
        write(secretKind, processFactory, targetName, accountName, token.Value);
    }

    public void writeTokenPair(final String targetName, final TokenPair tokenPair) {
        if (tokenPair.AccessToken.Value != null) {
            writeTokenKind(targetName, SecretKind.TokenPair_Access_Token, tokenPair.AccessToken);
        }

        if (tokenPair.RefreshToken.Value != null) {
            writeTokenKind(targetName, SecretKind.TokenPair_Refresh_Token, tokenPair.RefreshToken);
        }
    }
}
