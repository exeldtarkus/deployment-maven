package com.bcalife.common.deploy.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.bcalife.common.deploy.core.Deploy;

@Mojo(name = "k8s")
public class K8sMojo extends AbstractMojo {

    @Parameter(property = "projectId", defaultValue = "UNKNOWN")
    private String projectId;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            getLog().info("Starting Kubernetes Deployment...");
            Deploy.main(new String[]{projectId});
        } catch (Exception e) {
            throw new MojoExecutionException("K8s deployment failed", e);
        }
    }
}