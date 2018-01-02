

function GetSch() {

          const address = document.getElementById("LeaptestControllerURL").value;
          const accessKey = document.getElementById("LeaptestAccessKey").value;


          if(!address || !accessKey)
          {
          alert('"Address or Access Key field is empty! Cannot connect to server!"');
          }
          else
          {

              if(document.getElementById('MyContainer').innerHTML == "")
              {

                  (jQuery).ajax({
                      url: address + "/api/v1/runSchedules",
                      headers: { 'AccessKey': accessKey, 'Integration':'Jenkins-Integration' },
                      type: 'GET',
                      dataType:"json",
                      success: function(json)
                      {
                            const container = document.getElementById("MyContainer");


                            (jQuery)(document).click(function (event) {
                                if ((jQuery)(event.target).closest('#MyContainer').length == 0 && (jQuery)(event.target).attr('id') != 'mainButton') {
                                    (jQuery)("#MyContainer input:checkbox").remove();
                                    (jQuery)("#MyContainer li").remove();
                                    (jQuery)("#MyContainer ul").remove();
                                    (jQuery)("#MyContainer br").remove();
                                    container.style.display = 'none';
                                }
                            });


                            let schName = new Array();
                            let schId = new Array();
                            let schProjectId = new Array();

                            for (let i = 0; i < json.length; i++) {
                                if (json[i].IsDisplayedInScheduleList == true) {
                                    schId.push(json[i].Id);
                                    schName.push(json[i].Title);
                                    schProjectId.push(json[i].ProjectId);
                                }
                            }

                            let projects = new Array();
                            (jQuery).ajax({
                                url: address + "/api/v1/Projects",
                                headers: { 'AccessKey': accessKey, 'Integration':'Jenkins-Integration'},
                                type: 'GET',
                                dataType: "json",
                                success: function(projJson)
                                {
                                    for(let i = 0; i < projJson.length; i++)
                                    {
                                    projects.push(projJson[i].Title);
                                    }

                                    for(let i = 0; i < schProjectId.length; i++)
                                    {
                                        for(let j = 0; j < projJson.length; j++)
                                        {

                                            if(schProjectId[i] == projJson[j].Id)
                                            {
                                                schProjectId[i] = projJson[j].Title;
                                            }
                                        }
                                    }
                                    projJson = null;

                                    container.innerHTML += '<br>';

                                    let drpdwn = document.createElement('ul');
                                    drpdwn.className = 'ul-treefree ul-dropfree';

                                    for(let i = 0; i < projects.length; i++)
                                    {
                                        const projectli = document.createElement('li');

                                        const drop = document.createElement('div');
                                        drop.class = 'drop';
                                        drop.style = 'background-position: 0px 0px;';
                                        projectli.appendChild(drop);
                                        projectli.innerHTML+=projects[i];

                                        const schul = document.createElement('ul');
                                        schul.style = 'display:none;  font-weight: normal;';

                                        for(let j = 0; j < schProjectId.length; j++)
                                        {
                                            if(projects[i] == schProjectId[j])
                                            {
                                                let schli = document.createElement('li');
                                                let chbx = document.createElement('input');
                                                chbx.type = 'checkbox';
                                                chbx.name = schName[j];
                                                chbx.id = i;
                                                chbx.value = schId[j];

                                                schli.appendChild(chbx);
                                                schli.innerHTML+=schName[j];
                                                schul.appendChild(schli);
                                            }
                                        }

                                        projectli.appendChild(schul);
                                        drpdwn.appendChild(projectli);
                                    }

                                    container.appendChild(drpdwn);



                                     container.innerHTML += '<br>';

                                     container.style.display='block';

                                     (jQuery)(".ul-dropfree").find("li:has(ul)").prepend('<div class="drop"></div>');
                                     	(jQuery)(".ul-dropfree div.drop").click(function() {
                                     		if ((jQuery)(this).nextAll("ul").css('display')=='none') {
                                     			(jQuery)(this).nextAll("ul").slideDown(400);
                                     			(jQuery)(this).css({'background-position':"-11px 0"});
                                     		} else {
                                     			(jQuery)(this).nextAll("ul").slideUp(400);
                                     			(jQuery)(this).css({'background-position':"0 0"});
                                     		}
                                     	});
                                     	(jQuery)(".ul-dropfree").find("ul").slideUp(400).parents("li").children("div.drop").css({'background-position':"0 0"});

                                     let TestNames = document.getElementById("schNames");
                                     let TestIds = document.getElementById("schIds");

                                     let boxes = (jQuery)("#MyContainer input:checkbox");
                                     let existingTests = new Array();
                                     existingTests = TestNames.value.split("\n");

                                     if (TestNames.value != null && TestIds.value != null) {
                                            for (let i = 0; i < existingTests.length; i++) {
                                                for (j = 0; j < boxes.length; j++)
                                                {

                                                    if (existingTests[i] == boxes[j].getAttributeNode('name').value)
                                                     {
                                                   (jQuery)(boxes[j]).prop('checked', 'checked');

                                                    }
                                                }
                                            }

                                     }

                                     (jQuery)("#MyContainer input:checkbox").on("change", function ()
                                     {
                                         const NamesArray = new Array();
                                         const IdsArray = new Array();
                                         for (let i = 0; i < boxes.length; i++)
                                         {
                                              const box = boxes[i];
                                              if ((jQuery)(box).prop('checked'))
                                              {
                                                    NamesArray[NamesArray.length] = (jQuery)(box).attr('name');
                                                    IdsArray[IdsArray.length] = (jQuery)(box).val();
                                              }
                                         }
                                         TestNames.value = NamesArray.join("\n");
                                         TestIds.value = IdsArray.join("\n");
                                         console.log(TestIds.value)
                                     });
                                },
                                error: function(XMLHttpRequest, textStatus, errorThrown)
                                {
                                  alert(
                                  "Error occurred! Cannot get the list of Projects!\n" +
                                  "Status: " + textStatus + "\n" +
                                  "Error: " + errorThrown + "\n" +
                                  "This may occur because of the next reasons:\n" +
                                  "1.Wrong Controller URL\n" +
                                  "2.Wrong access Key\n" +
                                  "3.Controller is not running or updating now, check it in services\n" +
                                  "4.Your Leaptest Controller port is blocked.\nUse 'netstat -na | find \"9000\"' command, The result should be:\n 0.0.0.0:9000  0.0.0.0:0  LISTENING\n" +
                                  "5.You are using https in controller URL, which is not supported. HTTP only!\n" +
                                  "6.Your browser has such a setting enabled that blocks any http requests from https\n" +
                                  "If nothing helps, please contact support https://leaptest.com/support"
                                  );
                                }
                            });
                      },
                      error: function(XMLHttpRequest, textStatus, errorThrown)
                      {
                                alert(
                                "Error occurred! Cannot get the list of Projects!\n" +
                                "Status: " + textStatus + "\n" +
                                "Error: " + errorThrown + "\n" +
                                "This may occur because of the next reasons:\n" +
                                "1.Wrong Controller URL\n" +
                                "2.Wrong access Key\n" +
                                "3.Controller is not running or updating now, check it in services\n" +
                                "4.Your Leaptest Controller port is blocked.\nUse 'netstat -na | find \"9000\"' command, The result should be:\n 0.0.0.0:9000  0.0.0.0:0  LISTENING\n" +
                                "5.You are using https in controller URL, which is not supported. HTTP only!\n" +
                                "6.Your browser has such a setting enabled that blocks any http requests from https\n" +
                                "If nothing helps, please contact support https://leaptest.com/support"
                                );
                      }
                  });
              }
              else
              {
                  (jQuery)("#MyContainer input:checkbox").remove();
                  (jQuery)("#MyContainer li").remove();
                  (jQuery)("#MyContainer ul").remove();
                  (jQuery)("#MyContainer br").remove();
                  GetSch();
              }

          }
    }

