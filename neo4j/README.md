How to set-up and install?
- git clone https://github.com/chikacow/use-neo4j.git
- cd neo4j
- mvn clean package
- move the neo4j-7.1.0.jar from use-neo4j/neo4j/target -> use-neo4j/use-assembly/src/main/resources/plugins/officalVersion
- Run the USE GUI
- Replace the plugin directory of USE to match with your local machine

How to sync model?
- Load your model on USE
- Connect to Neo4j
- Use the Sync model forward feature, then approve
- For syncing backward, use the sync backward feature, and choose the model you want to pull from Neo4j

How to sync object?
- You must sync the relevant model first
- Use sync object forward feature to sync the object from USE to Neo4j
- Use sync object backward feature to sync the object from Neo4j to USE


How to run OCL-Cypher?
- Set up your model and object in Neo4j
- Use the OCL Evaluator from Neo4j to validate the state in Neo4j


How to run TGG-Cypher?

- Set up Neo4j DB on your local device first! Or having stuff on cloud if you are rich
- Connect USE instance to the DB
![img1.png](readme_img/img1.png)

- Push Family model and Person model to Neo4j first
![img4.png](readme_img/img4.png)

- Go to Model trafo to create correspondence graph in neo4j. Copy the script and run in Neo4j desktop, or terminal, anything
  ![img3.png](readme_img/img3.png)
- After this you should see a new model in neo4j linking Family and Person


- Go to "TGG1" to prepare environment data for the rule you want to test, can take a look on this script. Be aware that main obj should be created as main obj, corr obj should be created as corr obj as well
![tgg_soil.png](readme_img/tgg_soil.png)
![img2.png](readme_img/img2.png)

- Go to TGG matching, load the tgg file (again) and Scan. Check if your rule have the "Found" status. Select the data in the right section to get the execution query. Run it to execute the rule, as well as creating new node and edge in Neo4j.
![img5.png](readme_img/img5.png)

- Go to Neo4j to check your output

Congrats! 
