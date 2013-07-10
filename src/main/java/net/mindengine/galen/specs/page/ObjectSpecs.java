package net.mindengine.galen.specs.page;

import java.util.LinkedList;
import java.util.List;

import net.mindengine.galen.specs.Spec;

public class ObjectSpecs {

    private String objectName;
    private List<Spec> specs = new LinkedList<Spec>();

    public ObjectSpecs(String objectName) {
        this.objectName = objectName;
    }

    public String getObjectName() {
        return this.objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public List<Spec> getSpecs() {
        return this.specs;
    }

    public void setSpecs(List<Spec> specs) {
        this.specs = specs;
    }

}
