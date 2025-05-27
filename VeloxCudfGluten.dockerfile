FROM apache/gluten:vcpkg-centos-8

ARG CUDA_VERSION=12-8

RUN dnf install dnf-plugins-core -y
RUN dnf config-manager --add-repo https://developer.download.nvidia.com/compute/cuda/repos/rhel8/x86_64/cuda-rhel8.repo

RUN dnf install -y \
cuda-compat-$CUDA_VERSION \
cuda-driver-devel-$CUDA_VERSION \
cuda-minimal-build-$CUDA_VERSION \
cuda-nvrtc-devel-$CUDA_VERSION

RUN dnf install -y java-11-openjdk-devel
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

RUN dnf install -y libgsasl

RUN mkdir -p /incubator-gluten
COPY ./ /incubator-gluten
# Build Gluten Jar
RUN source /opt/rh/gcc-toolset-11/enable && \
    cd /incubator-gluten && \
    ./dev/builddeps-veloxbe.sh --run_setup_script=ON --enable_hdfs=ON --enable_vcpkg=OFF --build_arrow=OFF --enable_gpu=ON

RUN source /opt/rh/gcc-toolset-11/enable && \
    cd /incubator-gluten && \
    mvn clean package -Pbackends-velox -Pceleborn -Piceberg -Pdelta -Pspark-3.4 -DskipTests 

RUN source /opt/rh/gcc-toolset-11/enable && \
    cd /incubator-gluten && \
    /incubator-gluten/dev/build-thirdparty.sh
