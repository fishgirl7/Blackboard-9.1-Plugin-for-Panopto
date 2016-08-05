package com.panopto.blackboard;

import com.panopto.blackboard.Utils;
import blackboard.admin.persist.course.CloneConfig;
import blackboard.admin.persist.course.CourseEventListener;
import blackboard.persist.Id;

public class EventListener implements  CourseEventListener{

	@Override
	public void courseCloned(Id arg0, Id arg1, CloneConfig arg2)
			throws Exception {
		// Implement your custom logic here.arg2.
        System.out.println("Course Cloned. Source ID: " + arg0.toExternalString() + " Target ID: " + arg1.toExternalString());
		Utils.log("Course Cloned. Source ID: " + arg0.toExternalString() + " Target ID: " + arg1.toExternalString());

	}

	@Override
	public void courseCreated(Id arg0) {
		// TODO Auto-generated method stub
		
	}
	
	
}
