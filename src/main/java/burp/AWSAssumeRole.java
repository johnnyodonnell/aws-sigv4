package burp;

import burp.error.AWSCredentialProviderException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.StsException;

import javax.swing.*;
import java.util.regex.Pattern;

public class AWSAssumeRole implements AWSCredentialProvider, Cloneable
{
    public static final Pattern externalIdPattern = Pattern.compile("^[a-zA-Z0-9=@:/,._-]{2,1024}$");
    public static final Pattern roleArnPattern = Pattern.compile("^arn:aws:iam::[0-9]{12}:role/[0-9a-zA-Z+=,.@_-]{1,64}$"); // regionless
    public static final Pattern roleSessionNamePattern = Pattern.compile("^[a-zA-Z0-9+=@,._-]{2,64}$");
    public static final String PROVIDER_NAME = "STSAssumeRole";

    private String roleArn;
    private String sessionName;
    private int durationSeconds;
    private String externalId;

    private AWSTemporaryCredential temporaryCredential;
    private AWSPermanentCredential permanentCredential;
    private final transient BurpExtender burp = BurpExtender.getBurp();

    private static long CREDENTIAL_RENEWAL_AGE = 30; // seconds before expiration
    public static final int CREDENTIAL_LIFETIME_MIN = 900;
    public static final int CREDENTIAL_LIFETIME_MAX = 43200;
    public static final String ROLE_SESSION_NAME_DEFAULT_PREFIX = "BurpAwsig";

    public String getRoleArn()
    {
        return this.roleArn;
    }
    public String getExternalId() { return this.externalId; }
    public String getSessionName()
    {
        return this.sessionName;
    }
    public int getDurationSeconds()
    {
        return this.durationSeconds;
    }

    public AWSPermanentCredential getPermanentCredential()
    {
        return this.permanentCredential;
    }

    private AWSAssumeRole() {};

    private AWSAssumeRole(final String roleArn, final AWSPermanentCredential credential)
    {
        setRoleArn(roleArn);
        this.permanentCredential = credential;
        this.sessionName = createDefaultRoleSessionName();
        this.durationSeconds = CREDENTIAL_LIFETIME_MIN;
        this.externalId = "";
    }

    private void setExternalId(final String externalId) {
        if (externalIdPattern.matcher(externalId).matches())
            this.externalId = externalId;
        else
            throw new IllegalArgumentException("AWSAssumeRole externalId must match pattern "+externalIdPattern.pattern());
    }

    private void setDurationSeconds(int durationSeconds)
    {
        // duration must be in range [900, 43200]
        if (durationSeconds < CREDENTIAL_LIFETIME_MIN) {
            durationSeconds = CREDENTIAL_LIFETIME_MIN;
        }
        else if (durationSeconds > CREDENTIAL_LIFETIME_MAX) {
            durationSeconds = CREDENTIAL_LIFETIME_MAX;
        }
        this.durationSeconds = durationSeconds;
    }

    private void setRoleArn(final String roleArn)
    {
        if (roleArnPattern.matcher(roleArn).matches())
            this.roleArn = roleArn;
        else
            throw new IllegalArgumentException("AWSAssumeRole roleArn must match pattern "+roleArnPattern.pattern());
    }

    private void setRoleSessionName(final String roleSessionName)
    {
        if (roleSessionNamePattern.matcher(roleSessionName).matches())
            this.sessionName = roleSessionName;
        else
            throw new IllegalArgumentException("AWSAssumeRole roleSessionName must match pattern "+roleSessionNamePattern.pattern());
    }

    protected AWSAssumeRole clone()
    {
        return new AWSAssumeRole.Builder(this.roleArn, this.permanentCredential)
                .withDurationSeconds(this.durationSeconds)
                .withRoleSessionName(this.sessionName)
                .tryExternalId(this.externalId)
                .build();
    }

    public static class Builder {
        private AWSAssumeRole assumeRole;
        public Builder(final String roleArn, final AWSPermanentCredential credential) {
            this.assumeRole = new AWSAssumeRole(roleArn, credential);
        }
        public Builder(final AWSAssumeRole assumeRole) {
            this.assumeRole = assumeRole.clone();
        }
        // with -> strict, try -> lax
        public Builder withRoleArn(final String roleArn) {
            this.assumeRole.setRoleArn(roleArn);
            return this;
        }
        public Builder withRoleSessionName(final String sessionName) {
            this.assumeRole.setRoleSessionName(sessionName);
            return this;
        }
        public Builder tryRoleSessionName(final String sessionName) {
            if (sessionName != null && !sessionName.equals(""))
                withRoleSessionName(sessionName);
            else
                this.assumeRole.sessionName = createDefaultRoleSessionName();
            return this;
        }
        public Builder withDurationSeconds(final int durationSeconds) {
            this.assumeRole.setDurationSeconds(durationSeconds);
            return this;
        }
        public Builder withCredential(AWSPermanentCredential credential) {
            if (credential == null) {
                throw new IllegalArgumentException("AssumeRole permanent credential cannot be null");
            }
            this.assumeRole.permanentCredential = credential;
            return this;
        }
        public Builder withExternalId(final String externalId) {
            this.assumeRole.setExternalId(externalId);
            return this;
        }
        public Builder tryExternalId(final String externalId) {
            if (externalId != null && !externalId.equals(""))
                withExternalId(externalId);
            else
                this.assumeRole.externalId = "";
            return this;
        }
        public AWSAssumeRole build() {
            return this.assumeRole;
        }
    }

    private static String createDefaultRoleSessionName()
    {
        return String.format("%s_%d", ROLE_SESSION_NAME_DEFAULT_PREFIX, System.currentTimeMillis());
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public String getClassName() { return getClass().getName(); }

    @Override
    public AWSCredential getCredential() throws AWSCredentialProviderException
    {
        if ((this.temporaryCredential == null) || (this.temporaryCredential.secondsToExpire() < CREDENTIAL_RENEWAL_AGE)) {
            // signature is expired or about to expire. get new credentials
            renewCredential();
        }
        if (this.temporaryCredential == null) {
            throw new AWSCredentialProviderException("Failed to retrieve temp credentials for: "+this.roleArn);
        }
        return temporaryCredential;
    }

    /*
    fetch new temporary credentials.
     */
    private void renewCredential() throws AWSCredentialProviderException
    {
        burp.logger.info("Fetching temporary credentials for role "+this.roleArn);
        this.temporaryCredential = null;

        StsClient stsClient = StsClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(() -> AwsBasicCredentials.create(permanentCredential.getAccessKeyId(), permanentCredential.getSecretKey()))
                .build();

        AssumeRoleRequest.Builder requestBuilder = AssumeRoleRequest.builder()
                .roleArn(this.roleArn)
                .roleSessionName(this.sessionName)
                .durationSeconds(this.durationSeconds);
        if (this.externalId != null && !this.externalId.equals("")) {
            requestBuilder.externalId(this.externalId);
        }

        try {
            AssumeRoleResponse roleResponse = stsClient.assumeRole(requestBuilder.build());
            Credentials creds = roleResponse.credentials();
            this.temporaryCredential = new AWSTemporaryCredential(
                    creds.accessKeyId(),
                    creds.secretAccessKey(),
                    creds.sessionToken(),
                    creds.expiration().getEpochSecond());
        } catch (StsException exc) {
            JOptionPane.showMessageDialog(BurpExtender.getBurp().getUiComponent(), exc.getMessage());
        }
    }

}
