# Documentation for TimeOrderWindow.
#
# + f - documentation
public type TimeOrderWindow object {

    public function (int i) f;

    public function init() {
        // Test lambda function in object init.
        self.f = function (int i) {

        };
    }
};
