const fs = require("fs");
const core = require("@actions/core");
const github = require("@actions/github");
const { PropertiesEditor } = require("properties-file/editor");
const { getProperties } = require("properties-file");

(async () => {
    try {
        const contents = fs.readFileSync('gradle.properties').toString();
        const immutableProperties = getProperties(contents)
        const properties = new PropertiesEditor(contents)
        
        const res = await fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", {})
        if (!res.ok) {
            core.setFailed(`Failed to fetch data from piston meta: ${res.status} ${res.statusText}`);
            return;
        }
        
        const versionManifest = await res.json();

        let hasUpdated = false;

        const cLatestRelease = immutableProperties.latest_release;
        const cLatestSnapshot = immutableProperties.latest_snapshot;
        
        const mLatestRelease = versionManifest.latest.release.toString();
        const mLatestSnapshot = versionManifest.latest.snapshot.toString();

        console.log("---------------------------------------------------------");
        console.log("Current release version: " + cLatestRelease);
        console.log("Current snapshot version: " + cLatestSnapshot);
        console.log("---------------------------------------------------------");
        console.log("Latest release version: " + mLatestRelease);
        console.log("Latest snapshot version: " + mLatestSnapshot);
        console.log("---------------------------------------------------------");
        
        if (mLatestRelease !== cLatestRelease) {
            hasUpdated = true;
            properties.update("latest_release", {
                newValue: mLatestRelease,
            })
        }
        
        if (mLatestSnapshot !== cLatestSnapshot) {
            hasUpdated = true;
            properties.update("latest_snapshot", {
                newValue: mLatestSnapshot,
            })
        }
        
        if (hasUpdated) {
            const octokit = github.getOctokit(core.getInput("GITHUB_TOKEN"));

            const owner = github.context.repo.owner;
            const repo = github.context.repo.repo;
            
            const getFile = await octokit.rest.repos.getContent({
                owner,
                repo,
                path: "gradle.properties",
            });
            
            await octokit.rest.repos.createOrUpdateFileContents({
                owner,
                repo,
                path: "gradle.properties",
                message: "Update Mapping Versions",
                content: btoa(properties.format()),
                sha: getFile.data.sha
            })
        }
        
    } catch (error) {
        core.setFailed(error.message);
    }
})();