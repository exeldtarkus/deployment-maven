package com.bcalife.common.deploy.mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.bcalife.common.deploy.core.DeployTomcat;

@Mojo(name = "tomcat")
public class TomcatMojo extends AbstractMojo {

    @Parameter(property = "projectId", defaultValue = "UNKNOWN")
    private String projectId;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            getLog().info("Starting Tomcat Deployment...");
            DeployTomcat.main(new String[]{projectId});
        } catch (Exception e) {
            throw new MojoExecutionException("Tomcat deployment failed", e);
        }
    }
}